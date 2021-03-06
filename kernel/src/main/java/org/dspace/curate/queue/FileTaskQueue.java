/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */

package org.dspace.curate.queue;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.dspace.core.ConfigurationManager;
import org.dspace.core.Context;

/**
 * FileTaskQueue provides a TaskQueue implementation based on flat files
 * for the queues and semaphores. It should be considered deprecated in
 * favor of DBTaskQueue (RDBMS-backed queue), but is included for backward
 * compatibility/migration. For new installations, configure DBTaskQueue.
 *
 * @author richardrodgers
 */
@Deprecated
public class FileTaskQueue implements TaskQueue {

    private static Logger log = LoggerFactory.getLogger(FileTaskQueue.class);   
    // base directory for curation task queues
    private String tqDir = ConfigurationManager.getProperty("curate", "taskqueue.dir");

    // ticket for queue readers
    private long readTicket = -1L;
    // list of queues owned by reader
    private List<Integer> readList = new ArrayList<>();

    public FileTaskQueue() {}
    
    @Override
    public List<String> queueNames(Context context) {
        return Arrays.asList(new File(tqDir).list());
    }
    
    @Override
    public synchronized void enqueue(Context context, String queueName, TaskQueueEntry entry) throws IOException, SQLException {
        Set entrySet = new HashSet<TaskQueueEntry>();
        entrySet.add(entry);
        enqueue(context, queueName, entrySet);
    }

    @Override
    public synchronized void enqueue(Context context, String queueName, Set<TaskQueueEntry> entrySet) throws IOException, SQLException {
        // don't block or fail - iterate until an unlocked queue found/created
        int queueIdx = 0;
        File qDir = ensureQueue(queueName);
        while (true) {
            File lock = new File(qDir, "lock" + Integer.toString(queueIdx));

            // Check for lock, and create one if it doesn't exist.
            // If the lock file already exists, this will return false
            if (lock.createNewFile()) {
                // append set contents to queue
                File queue = new File(qDir, "queue" + Integer.toString(queueIdx));
                try (BufferedWriter writer = new BufferedWriter(new FileWriter(queue, true))) {
                    Iterator<TaskQueueEntry> iter = entrySet.iterator();
                    while (iter.hasNext()) {
                        writer.write(iter.next().toString());
                        writer.newLine();
                    }
                }
                // remove lock
                if (!lock.delete()) {
                    log.error("Unable to remove lock: " + lock.getName());
                }
                break;
            }
            queueIdx++;
        }
    }

    @Override
    public synchronized Set<TaskQueueEntry> dequeue(Context context, String queueName, long ticket) throws IOException, SQLException {
        Set<TaskQueueEntry> entrySet = new HashSet<TaskQueueEntry>();
        if (readTicket == -1L) {
            // hold the ticket & copy all Ids available, locking queues
            // stop when no more queues or one found locked
            File qDir = ensureQueue(queueName);
            readTicket = ticket;
            int queueIdx = 0;
            while (true) {
                File queue = new File(qDir, "queue" + Integer.toString(queueIdx));
                File lock = new File(qDir, "lock" + Integer.toString(queueIdx));

                // If the queue file exists, atomically check for a lock file and create one if it doesn't exist
                // If the lock file exists already, then this simply returns false
                if (queue.exists() && lock.createNewFile()) {
                    // read contents from file
                    try (BufferedReader reader = new BufferedReader(new FileReader(queue))) {
                        String entryStr = null;
                        while ((entryStr = reader.readLine()) != null) {
                            entryStr = entryStr.trim();
                            if (entryStr.length() > 0) {
                                entrySet.add(new TaskQueueEntry(entryStr));
                            }
                        }
                    }
                    readList.add(queueIdx);
                } else {
                    break;
                }
                queueIdx++;
            }
        }
        return entrySet;
    }

    @Override
    public Set<TaskQueueEntry> peek(Context context, String queueName) throws SQLException {
        throw new UnsupportedOperationException("Not implemented for files");
    }
    
    @Override
    public synchronized void release(Context context, String queueName, long ticket, boolean remove) throws SQLException {
        if (ticket == readTicket) {
            readTicket = -1L;
            File qDir = ensureQueue(queueName);
            // remove locks & queues (if flag true)
            for (Integer queueIdx : readList) {
                File lock = new File(qDir, "lock" + Integer.toString(queueIdx));
                if (remove) {
                    File queue = new File(qDir, "queue" + Integer.toString(queueIdx));
                    if (!queue.delete()) {
                        log.error("Unable to delete queue file: " + queue.getName());
                    }
                }
                if (!lock.delete()) {
                    log.error("Unable to delete lock file: " + lock.getName());
                }
            }
            readList.clear();
        }
    }
    
    private File ensureQueue(String queueName) {
        // create directory structures as needed
        File baseDir = new File(tqDir, queueName);
        if (!baseDir.exists() && !baseDir.mkdirs()) {
            throw new IllegalStateException("Unable to create directories");
        }
        return baseDir;
    }
}

/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.webapi.curation;

import java.io.IOException;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import javax.ws.rs.GET;
import javax.ws.rs.Path;
import javax.ws.rs.PathParam;
import javax.ws.rs.POST;
import javax.ws.rs.Produces;
import javax.ws.rs.WebApplicationException;
import javax.ws.rs.core.Context;
import javax.ws.rs.core.Response;
import javax.ws.rs.core.Response.ResponseBuilder;
import javax.ws.rs.core.SecurityContext;

import static javax.ws.rs.core.MediaType.*;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.dspace.authorize.AuthorizeException;
import org.dspace.curate.Curator;
import org.dspace.curate.ObjectSelector;
import org.dspace.curate.SelectorResolver;
import org.dspace.eperson.EPerson;

import org.dspace.webapi.curation.domain.Action;
import org.dspace.webapi.curation.domain.Curation;
import org.dspace.webapi.curation.domain.CurationOrder;
import org.dspace.webapi.curation.domain.Selector;
import org.dspace.webapi.curation.domain.SelectorGroup;
import org.dspace.webapi.curation.domain.Task;
import org.dspace.webapi.curation.domain.TaskGroup;

/**
 * CurationResource is a JAX-RS root resource providing a REST API for curation.
 * Through the API, one can enumerate tasks and task groups, (named) selectors
 * and selector groups, and invoke or queue Dspace Objects against them.
 * Only mildly RESTalicious - no HATEOAS, etc
 * 
 * @author richardrodgers
 */

@Path("curation")
@Produces({APPLICATION_XML, APPLICATION_JSON})
public class CurationResource {

    private static Logger log = LoggerFactory.getLogger(CurationResource.class);

    private final CurationDao curationDao = new CurationDao();
    private final Map<String, String> statusMap = curationDao.getStatusMap();

    @GET @Path("tasks")
    public List<Task> getTasks() {
        return curationDao.getTasks();
    }

    @GET @Path("taskgroups")
    public List<TaskGroup> getTaskGroups() {
        return curationDao.getTaskGroups();
    }

    @GET @Path("selectors")
    public List<Selector> getSelectors() {
        return curationDao.getSelectors();
    }

    @GET @Path("selectorgroups")
    public List<SelectorGroup> getSelectorGroups() {
        return curationDao.getSelectorGroups();
    }

    @POST @Path("content/{dsoId}")
    public Curation curateDso(@Context SecurityContext sec, @PathParam("dsoId") String dsoId,
                              CurationOrder order) {
        Curation curation = initCuration(dsoId, "dso", sec, order);
        return curate(curation);
    }

    @POST @Path("set/{selector}")
    public Curation curateSelector(@Context SecurityContext sec, @PathParam("selector") String selector,
                                   CurationOrder order) {
        Curation curation = initCuration(selector, "set", sec, order);
        return curate(curation);
    }

    private Curation curate(Curation curation) {
        org.dspace.core.Context ctx = null;
        EPerson invoker = null;
        Curator curator = null;
        try {
            ctx = new org.dspace.core.Context();
            invoker = EPerson.findByEmail(ctx, curation.getInvoker());
            if (invoker != null) {
                ctx.setCurrentUser(invoker);
                curator = initCurator(curation);
                String queue = curation.getQueue();
                if ("dso".equals(curation.getIdType())) {
                    if (queue == null) {
                        curator.curate(ctx, curation.getId());
                    } else {
                        curator.queue(ctx, curation.getId(), queue);
                    }
                } else if ("set".equals(curation.getIdType())) {
                    ObjectSelector selector = SelectorResolver.resolveSelector(ctx, curation.getId());
                    if (selector != null) {
                        selector.setContext(ctx);
                        if (queue == null) {
                            curator.curate(selector);
                        } else {
                            curator.queue(selector, queue);
                        }
                    } else {
                        throw new WebApplicationException(Response.Status.NOT_FOUND);
                    }
                }
                updateCuration(curation, curator);
            } else {
                throw new WebApplicationException(Response.Status.UNAUTHORIZED);
            }
        } catch (AuthorizeException | IOException | SQLException e) {
            log.error("Error", e);
            throw new WebApplicationException();
        } finally {
            try {
                if (curator != null) {
                    curator.complete();
                }
                if (ctx != null) {
                    ctx.complete();
                }
            } catch (IOException | SQLException e) {
                log.error("Exception completing curator", e);
            }
        }
        return curation;
    }

    private Curation initCuration(String id, String idType, 
                                  SecurityContext sec, CurationOrder order) {
        Curation curation = new Curation();
        curation.setId(id);
        curation.setIdType(idType);
        curation.setInvoker(sec.getUserPrincipal().toString());
        Map<String, Task> knownTasks = curationDao.getTaskMap();
        for (String taskName : order.getTasks()) {
            Task task = knownTasks.get(taskName);
            if (task != null) {
                Action action = new Action();
                action.setTaskName(task.getName());
                action.setTaskDescription(task.getDescription());
                curation.addAction(action);
            } else {
                log.warn("Unknown task: '" + task + "' - skipped");
            }
        }
        if (curation.numberActions() == 0) {
            // no point in continuing - no tasks to work with
            throw new WebApplicationException(Response.Status.BAD_REQUEST);
        }
        String queue = order.getQueue();
        if (queue != null) {
            curation.setQueue(queue);
        }
        String scope = order.getTransactionScope();
        if (scope != null) {
            Curator.TxScope txScope = Curator.TxScope.valueOf(scope.toUpperCase());
            if (txScope != null) {
                curation.setTransactionScope(scope);
            } else {
                log.warn("Invalid transaction scope: '" + scope + "' - ignored");
            }
        }
        if (order.getCacheLimit() > 0) {
            curation.setCacheLimit(order.getCacheLimit());
        }
        if (order.getJournalFilter() != null) {
            curation.setJournalFilter(order.getJournalFilter());
        }
        return curation;
    }

    private void updateCuration(Curation curation, Curator curator) {
        for (Action action : curation.getActions()) {
            int code = curator.getStatus(action.getTaskName());
            action.setStatusCode(code);
            String key = statusMap.containsKey(String.valueOf(code)) ? String.valueOf(code) : "other";
            action.setStatusLabel(statusMap.get(key));
            action.setResult(curator.getResult(action.getTaskName()));
        }
    }

    private Curator initCurator(Curation curation) {
        Curator curator = new Curator();
        String scope = curation.getTransactionScope();
        if (scope != null) {
            curator.setTransactionScope(Curator.TxScope.valueOf(scope.toUpperCase()));
        }
        int cacheLimit = curation.getCacheLimit();
        if (cacheLimit > 0) {
            curator.setCacheLimit(cacheLimit);
        }
        String filter = curation.getJournalFilter();
        if (filter != null) {
            curator.setJournalFilter(filter);
        }
        for (Action action : curation.getActions()) {
            curator.addTask(action.getTaskName());
        }
        return curator;
    }
}

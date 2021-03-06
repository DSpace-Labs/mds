/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.embargo;

import java.sql.SQLException;
import java.io.IOException;
import java.util.Date;

import org.joda.time.DateTime;
import org.joda.time.format.DateTimeFormat;
import org.joda.time.format.DateTimeFormatter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.dspace.authorize.AuthorizeException;
import org.dspace.authorize.AuthorizeManager;
import org.dspace.authorize.ResourcePolicy;
import org.dspace.content.Item;
import org.dspace.content.Bitstream;
import org.dspace.content.Bundle;
import org.dspace.core.ConfigManager;
import org.dspace.core.Context;
import org.dspace.core.Constants;
//import org.dspace.license.CreativeCommons;

/**
 * Default plugin implementation of the embargo setting function.
 * The parseTerms() provides only very rudimentary terms logic - entry
 * of a configurable string (in terms field) for 'unlimited' embargo, otherwise
 * a standard ISO 8601 (yyyy-MM-dd) date is assumed. Users are encouraged 
 * to override this method for enhanced functionality.
 *
 * @author Larry Stone
 * @author Richard Rodgers
 */
public class DefaultEmbargoSetter implements EmbargoSetter
{
	 private static final Logger log = LoggerFactory.getLogger(DefaultEmbargoSetter.class);
	// declared in CreativeCommons.java - avoid this dependency TODO
	private static final String CC_BUNDLE_NAME = "CC-LICENSE";
	private static final DateTimeFormatter fmt = DateTimeFormat.forPattern("yyyy-MM-dd");
    protected String termsOpen = null;
	
    public DefaultEmbargoSetter() {
        super();
        termsOpen = ConfigManager.getProperty("embargo", "terms.open");
    }
    
    /**
     * Parse the terms into a definite date. Terms are expected to consist of
     * either: a token (value configured in 'embargo.terms.open' property) to indicate
     * indefinite embargo, or a literal lift date formatted in ISO 8601 format (yyyy-mm-dd)
     * 
     * @param context the DSpace context
     * @param item the item to embargo
     * @param terms the embargo terms
     * @return parsed date in Date format
     */
    @Override
    public Date parseTerms(Context context, Item item, String terms)
        throws SQLException, AuthorizeException, IOException {
    	if (terms != null && terms.length() > 0) {
    		Date result = EmbargoManager.FOREVER;
    		if (! termsOpen.equals(terms)) {
            	// attempt to parse terms as an ISO-8601 date
            	// with format 'yyyy-MM-dd' If we fail,
            	// presume that a future date *was* intended
            	// and err on the side of caution by setting
            	// embargo date to forever and logging the error
            	try {
            		DateTime dt = fmt.parseDateTime(terms);
            		result = dt.toDate();
            	} catch (IllegalArgumentException iaE) {
            		log.error("Unable to parse date format", iaE);
            		result = EmbargoManager.FOREVER;
            	}
            }
    		return result;
    	}
        return null;
    }

    /**
     * Enforce embargo by turning off all read access to bitstreams in
     * this Item.
     *
     * @param context the DSpace context
     * @param item the item to embargo
     */
    @Override
    public void setEmbargo(Context context, Item item)
        throws SQLException, AuthorizeException, IOException {
        for (Bundle bn : item.getBundles()) {
            // Skip the LICENSE and METADATA bundles, they stay world-readable
            String bnn = bn.getName();
            if (!(bnn.equals(Constants.LICENSE_BUNDLE_NAME) || bnn.equals(Constants.METADATA_BUNDLE_NAME) || bnn.equals(CC_BUNDLE_NAME)))
            {
                AuthorizeManager.removePoliciesActionFilter(context, bn, Constants.READ);
                for (Bitstream bs : bn.getBitstreams()) {
                    AuthorizeManager.removePoliciesActionFilter(context, bs, Constants.READ);
                }
            }
        }
    }

    /**
     * Check that embargo is properly set on Item: no read access to bitstreams.
     *
     * @param context the DSpace context
     * @param item the item to embargo
     */
    @Override
    public void checkEmbargo(Context context, Item item)
        throws SQLException, AuthorizeException, IOException {
        for (Bundle bn : item.getBundles()) {
            // Skip the LICENSE and METADATA bundles, they stay world-readable
            String bnn = bn.getName();
            if (!(bnn.equals(Constants.LICENSE_BUNDLE_NAME) || bnn.equals(Constants.METADATA_BUNDLE_NAME) || bnn.equals(CC_BUNDLE_NAME)))
            {
                // don't report on "TEXT" or "THUMBNAIL" bundles; those
                // can have READ long as the bitstreams in them do not.
                if (!(bnn.equals("TEXT") || bnn.equals("THUMBNAIL")))  {
                    // check for ANY read policies and report them:
                    for (ResourcePolicy rp : AuthorizeManager.getPoliciesActionFilter(context, bn, Constants.READ)) {
                        System.out.println("CHECK WARNING: Item "+item.getHandle()+", Bundle "+bn.getName()+" allows READ by "+
                          ((rp.getEPersonID() < 0) ? "Group "+rp.getGroup().getName() :
                                                      "EPerson "+rp.getEPerson().getFullName()));
                    }
                }

                for (Bitstream bs : bn.getBitstreams())  {
                    for (ResourcePolicy rp : AuthorizeManager.getPoliciesActionFilter(context, bs, Constants.READ)) {
                        System.out.println("CHECK WARNING: Item "+item.getHandle()+", Bitstream "+bs.getName()+" (in Bundle "+bn.getName()+") allows READ by "+
                          ((rp.getEPersonID() < 0) ? "Group "+rp.getGroup().getName() :
                                                      "EPerson "+rp.getEPerson().getFullName()));
                    }
                }
            }
        }
    }
}

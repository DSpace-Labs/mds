/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.content;

import java.sql.SQLException;
import java.util.Collection;
import java.util.List;

import org.dspace.AbstractUnitTest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.dspace.authorize.AuthorizeException;
import org.junit.*;
import static org.junit.Assert.* ;
import static org.hamcrest.CoreMatchers.*;

/**
 * Unit Tests for class MetadataValue
 * @author pvillega
 */
public class MetadataValueTest extends AbstractUnitTest
{

    /** log4j category */
    private static final Logger log = LoggerFactory.getLogger(MetadataValueTest.class);

    /**
     * MetadataValue instance for the tests
     */
    private MetadataValue mv;

    /**
     * MetadataField instance for the tests
     */
    private MetadataField mf;

    /**
     * Element of the metadata element
     */
    private String element = "contributor";

    /**
     * Qualifier of the metadata element
     */
    private String qualifier = "author";

    /**
     * This method will be run before every test as per @Before. It will
     * initialize resources required for the tests.
     *
     * Other methods can be annotated with @Before here or in subclasses
     * but no execution order is guaranteed
     */
    @Before
    @Override
    public void init()
    {
        super.init();
        try
        {
            context.turnOffAuthorisationSystem();
            this.mf = MetadataField.findByElement(context,
                    MetadataSchema.DC_SCHEMA_ID, element, qualifier);
            this.mv = new MetadataValue(mf);
            this.mv.setDsoId(Item.create(context).getDSOiD());
            context.commit();
            context.restoreAuthSystemState();
        }
        catch (AuthorizeException ex)
        {
            log.error("Authorize Error in init", ex);
            fail("Authorize Error in init");
        }
        catch (SQLException ex)
        {
            log.error("SQL Error in init", ex);
            fail("SQL Error in init");
        }
    }

    /**
     * This method will be run after every test as per @After. It will
     * clean resources initialized by the @Before methods.
     *
     * Other methods can be annotated with @After here or in subclasses
     * but no execution order is guaranteed
     */
    @After
    @Override
    public void destroy()
    {
        mf = null;
        mv = null;
        super.destroy();
    }

    /**
     * Test of getFieldId method, of class MetadataValue.
     */
    @Test
    public void testGetFieldId()
    {
        MetadataValue instance = new MetadataValue();
        assertThat("testGetFieldId 0", instance.getFieldId(), equalTo(0));

        assertThat("testGetFieldId 1", mv.getFieldId(), equalTo(mf.getFieldID()));
    }

    /**
     * Test of setFieldId method, of class MetadataValue.
     */
    @Test
    public void testSetFieldId()
    {
        int fieldId = 66;
        mv.setFieldId(fieldId);
        assertThat("testSetFieldId 0", mv.getFieldId(), equalTo(fieldId));
    }

    /**
     * Test of getItemId method, of class MetadataValue.
     */
    @Test
    public void testDsoId() 
    {
        assertTrue("testGetDsoId 0", mv.getDsoId() >= 0);
    }

    /**
     * Test of setItemId method, of class MetadataValue.
     */
    @Test
    public void testSetDsoId()
    {
        int dsoId = 55;
        mv.setDsoId(dsoId);
        assertThat("testSetDsoId 0", mv.getDsoId(), equalTo(dsoId));
    }

    /**
     * Test of getLanguage method, of class MetadataValue.
     */
    @Test
    public void testGetLanguage() 
    {
        assertThat("testGetLanguage 0", mv.getLanguage(), nullValue());
    }

    /**
     * Test of setLanguage method, of class MetadataValue.
     */
    @Test
    public void testSetLanguage()
    {
        String language = "eng";
        mv.setLanguage(language);
        assertThat("testSetLanguage 0", mv.getLanguage(), equalTo(language));
    }

    /**
     * Test of getPlace method, of class MetadataValue.
     */
    @Test
    public void testGetPlace()
    {
        assertThat("testGetPlace 0",mv.getPlace(), equalTo(1));
    }

    /**
     * Test of setPlace method, of class MetadataValue.
     */
    @Test
    public void testSetPlace()
    {
        int place = 5;
        mv.setPlace(place);
        assertThat("testSetPlace 0",mv.getPlace(), equalTo(place));
    }

    /**
     * Test of getValueId method, of class MetadataValue.
     */
    @Test
    public void testGetValueId() 
    {
        assertThat("testGetValueId 0",mv.getValueId(), equalTo(0));
    }

    /**
     * Test of getValue method, of class MetadataValue.
     */
    @Test
    public void testGetValue() 
    {
        assertThat("testGetValue 0",mv.getValue(), nullValue());
    }

    /**
     * Test of setValue method, of class MetadataValue.
     */
    @Test
    public void testSetValue()
    {
        String value = "value";
        mv.setValue(value);
        assertThat("testSetValue 0",mv.getValue(), equalTo(value));
    }

    /**
     * Test of create method, of class MetadataValue.
     */
    @Test
    public void testCreate() throws Exception
    {
        mv.create(context);
    }

    /**
     * Test of find method, of class MetadataValue.
     */
    @Test
    public void testFind() throws Exception 
    {
        mv.create(context);
        int id = mv.getValueId();
        MetadataValue found = MetadataValue.find(context, id);
        assertThat("testFind 0",found, notNullValue());
        assertThat("testFind 1",found.getValueId(), equalTo(id));
    }

    /**
     * Test of findByField method, of class MetadataValue.
     */
    @Test
    public void testFindByField() throws Exception
    {
        mv.create(context);
        int fieldId = mv.getFieldId();
        List<MetadataValue> found = MetadataValue.findByField(context, fieldId);
        assertThat("testFind 0",found, notNullValue());
        assertTrue("testFind 1",found.size() >= 1);        
    }

    /**
     * Test of update method, of class MetadataValue.
     */
    @Test
    public void testUpdate() throws Exception
    {
        mv.create(context);
        mv.update(context);
    }

    /**
     * Test of delete method, of class MetadataValue.
     */
    @Test
    public void testDelete() throws Exception
    {
        mv.create(context);
        int id = mv.getValueId();
        mv.delete(context);
        MetadataValue found = MetadataValue.find(context, id);
        assertThat("testDelete 0",found, nullValue());
    }

}

/**
 * The contents of this file are subject to the license and copyright
 * detailed in the LICENSE and NOTICE files at the root of the source
 * tree and available online at
 *
 * http://www.dspace.org/license/
 */
package org.dspace.identifier;

import org.apache.log4j.Logger;
import org.dspace.authorize.AuthorizeException;
import org.dspace.content.*;
import org.dspace.content.service.ItemService;
import org.dspace.core.ConfigurationManager;
import org.dspace.core.Constants;
import org.dspace.core.Context;
import org.dspace.core.LogManager;
import org.dspace.handle.service.HandleService;
import org.dspace.versioning.*;
import org.dspace.versioning.service.VersionHistoryService;
import org.dspace.versioning.service.VersioningService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.sql.SQLException;
import java.util.Date;
import java.util.List;

/**
 *
 *
 * @author Fabio Bolognesi (fabio at atmire dot com)
 * @author Mark Diggory (markd at atmire dot com)
 * @author Ben Bosman (ben at atmire dot com)
 */
@Component
public class VersionedHandleIdentifierProvider extends IdentifierProvider {
    /** log4j category */
    private static Logger log = Logger.getLogger(VersionedHandleIdentifierProvider.class);

    /** Prefix registered to no one */
    static final String EXAMPLE_PREFIX = "123456789";

    private static final char DOT = '.';

    private String[] supportedPrefixes = new String[]{"info:hdl", "hdl", "http://"};

    @Autowired(required = true)
    private VersioningService versionService;

    @Autowired(required = true)
    private VersionHistoryService versionHistoryService;

    @Autowired(required = true)
    private HandleService handleService;

    @Autowired(required = true)
    private ItemService itemService;

    @Override
    public boolean supports(Class<? extends Identifier> identifier)
    {
        return Handle.class.isAssignableFrom(identifier);
    }

    @Override
    public boolean supports(String identifier)
    {
        for(String prefix : supportedPrefixes)
        {
            if(identifier.startsWith(prefix))
            {
                return true;
            }
        }

        try {
            String outOfUrl = retrieveHandleOutOfUrl(identifier);
            if(outOfUrl != null)
            {
                return true;
            }
        } catch (SQLException e) {
            log.error(e.getMessage(), e);
        }

        return false;
    }

    @Override
    public String register(Context context, DSpaceObject dso)
    {
        try
        {
            String id = mint(context, dso);

            // move canonical to point the latest version
            if(dso != null && dso.getType() == Constants.ITEM)
            {
                Item item = (Item)dso;
                VersionHistory history = versionHistoryService.findByItem(context, (Item) dso);
                if(history!=null)
                {
                    String canonical = getCanonical(context, item);
                    // Modify Canonical: 12345/100 will point to the new item
                    handleService.modifyHandleDSpaceObject(context, canonical, item);

                    // in case of first version we have to modify the previous metadata to be xxxx.1
                    Version version = versionService.getVersion(context, item);
                    Version previous = versionHistoryService.getPrevious(history, version);
                    if (versionHistoryService.isFirstVersion(history, previous))
                    {
                        try{
                            //If we have a reviewer he/she might not have the rights to edit the metadata of this item, so temporarly grant them.
                            context.turnOffAuthorisationSystem();
                            modifyHandleMetadata(context, previous.getItem(), (canonical + DOT + 1));
                        }finally {
                            context.restoreAuthSystemState();
                        }

                    }
                    // Check if our previous item hasn't got a handle anymore.
                    // This only occurs when a switch has been made from the standard handle identifier provider
                    // to the versioned one, in this case no "versioned handle" is reserved so we need to create one
                    if(previous != null && handleService.findHandle(context, previous.getItem()) == null){
                        makeIdentifierBasedOnHistory(context, previous.getItem(), canonical, history);

                    }
                }
                populateHandleMetadata(context, item);
            }

            return id;
        }catch (Exception e){
            log.error(LogManager.getHeader(context, "Error while attempting to create handle", "Item id: " + (dso != null ? dso.getID() : "")), e);
            throw new RuntimeException("Error while attempting to create identifier for Item id: " + (dso != null ? dso.getID() : ""));
        }
    }

    @Override
    public void register(Context context, DSpaceObject dso, String identifier)
    {
        try
        {

            Item item = (Item) dso;

            // if for this identifier is already present a record in the Handle table and the corresponding item
            // has an history someone is trying to restore the latest version for the item. When
            // trying to restore the latest version the identifier in input doesn't have the for 1234/123.latestVersion
            // it is the canonical 1234/123
            VersionHistory itemHistory = getHistory(context, identifier);
            if(!identifier.matches(".*/.*\\.\\d+") && itemHistory!=null){

                int newVersionNumber = versionHistoryService.getLatestVersion(itemHistory).getVersionNumber()+1;
                String canonical = identifier;
                identifier = identifier.concat(".").concat("" + newVersionNumber);
                restoreItAsVersion(context, dso, identifier, item, canonical, itemHistory);
            }
            // if identifier == 1234.5/100.4 reinstate the version 4 in the version table if absent
            else if(identifier.matches(".*/.*\\.\\d+"))
            {
                // if it is a version of an item is needed to put back the record
                // in the versionitem table
                String canonical = getCanonical(identifier);
                DSpaceObject canonicalItem = this.resolve(context, canonical);
                if(canonicalItem==null){
                    restoreItAsCanonical(context, dso, identifier, item, canonical);
                }
                else{
                    VersionHistory history = versionHistoryService.findByItem(context, (Item) canonicalItem);
                    if(history==null){
                        restoreItAsCanonical(context, dso, identifier, item, canonical);
                    }
                    else
                    {
                        restoreItAsVersion(context, dso, identifier, item, canonical, history);

                    }
                }
            }
            else
            {
                //A regular handle
                createNewIdentifier(context, dso, identifier);
                if(dso instanceof Item)
                {
                    populateHandleMetadata(context, item);
                }
            }
        }catch (Exception e){
            log.error(LogManager.getHeader(context, "Error while attempting to create handle", "Item id: " + dso.getID()), e);
            throw new RuntimeException("Error while attempting to create identifier for Item id: " + dso.getID(), e);
        }
    }

    protected VersionHistory getHistory(Context context, String identifier) throws SQLException {
        DSpaceObject item = this.resolve(context, identifier);
        if(item!=null){
            VersionHistory history = versionHistoryService.findByItem(context, (Item) item);
            return history;
        }
        return null;
    }

    protected void restoreItAsVersion(Context context, DSpaceObject dso, String identifier, Item item, String canonical, VersionHistory history) throws SQLException, IOException, AuthorizeException
    {
        createNewIdentifier(context, dso, identifier);
        populateHandleMetadata(context, item);

        int versionNumber = Integer.parseInt(identifier.substring(identifier.lastIndexOf(".") + 1));
        versionService.createNewVersion(context, history, item, "Restoring from AIP Service", new Date(), versionNumber);
        Version latest = versionHistoryService.getLatestVersion(history);


        // if restoring the lastest version: needed to move the canonical
        if(latest.getVersionNumber() < versionNumber){
            handleService.modifyHandleDSpaceObject(context, canonical, dso);
        }
    }

    protected void restoreItAsCanonical(Context context, DSpaceObject dso, String identifier, Item item, String canonical) throws SQLException, IOException, AuthorizeException
    {
        createNewIdentifier(context, dso, identifier);
        populateHandleMetadata(context, item);

        int versionNumber = Integer.parseInt(identifier.substring(identifier.lastIndexOf(".")+1));
        VersionHistory history=versionHistoryService.create(context);
        versionService.createNewVersion(context, history, item, "Restoring from AIP Service", new Date(), versionNumber);

        handleService.modifyHandleDSpaceObject(context, canonical, dso);

    }


    @Override
    public void reserve(Context context, DSpaceObject dso, String identifier)
    {
        try{
            handleService.createHandle(context, dso, identifier);
        }catch(Exception e){
            log.error(LogManager.getHeader(context, "Error while attempting to create handle", "Item id: " + dso.getID()), e);
            throw new RuntimeException("Error while attempting to create identifier for Item id: " + dso.getID());
        }
    }


    /**
     * Creates a new handle in the database.
     *
     * @param context DSpace context
     * @param dso The DSpaceObject to create a handle for
     * @return The newly created handle
     */
    @Override
    public String mint(Context context, DSpaceObject dso)
    {
        if(dso.getHandle() != null)
        {
            return dso.getHandle();
        }

        try{
            String handleId = null;
            VersionHistory history = null;
            if(dso instanceof Item)
            {
                history = versionHistoryService.findByItem(context, (Item) dso);
            }

            if(history!=null)
            {
                handleId = makeIdentifierBasedOnHistory(context, dso, handleId, history);
            }else{
                handleId = createNewIdentifier(context, dso, null);
            }
            return handleId;
        }catch (Exception e){
            log.error(LogManager.getHeader(context, "Error while attempting to create handle", "Item id: " + dso.getID()), e);
            throw new RuntimeException("Error while attempting to create identifier for Item id: " + dso.getID());
        }
    }

    @Override
    public DSpaceObject resolve(Context context, String identifier, String... attributes)
    {
        // We can do nothing with this, return null
        try{
            return handleService.resolveToObject(context, identifier);
        }catch (Exception e){
            log.error(LogManager.getHeader(context, "Error while resolving handle to item", "handle: " + identifier), e);
        }
        return null;
    }

    @Override
    public String lookup(Context context, DSpaceObject dso) throws IdentifierNotFoundException, IdentifierNotResolvableException {

        try
        {
            return handleService.findHandle(context, dso);
        }catch(SQLException sqe){
            throw new IdentifierNotResolvableException(sqe.getMessage(),sqe);
        }
    }

    @Override
    public void delete(Context context, DSpaceObject dso, String identifier) throws IdentifierException {
        delete(context, dso);
    }

    @Override
    public void delete(Context context, DSpaceObject dso) throws IdentifierException {

        try {
            if (dso instanceof Item)
            {
                Item item = (Item) dso;

                // If it is the most current version occurs to move the canonical to the previous version
                VersionHistory history = versionHistoryService.findByItem(context, item);
                if(history!=null && versionHistoryService.getLatestVersion(history).getItem().equals(item) && history.getVersions().size() > 1)
                {
                    Item previous = versionHistoryService.getPrevious(history, versionHistoryService.getLatestVersion(history)).getItem();

                    // Modify Canonical: 12345/100 will point to the new item
                    String canonical = getCanonical(context, previous);
                    handleService.modifyHandleDSpaceObject(context, canonical, previous);
                }
            }
        } catch (Exception e) {
            log.error(LogManager.getHeader(context, "Error while attempting to register doi", "Item id: " + dso.getID()), e);
            throw new IdentifierException("Error while moving doi identifier", e);
        }


    }

    public static String retrieveHandleOutOfUrl(String url) throws SQLException
    {
        // We can do nothing with this, return null
        if (!url.contains("/")) return null;

        String[] splitUrl = url.split("/");

        return splitUrl[splitUrl.length - 2] + "/" + splitUrl[splitUrl.length - 1];
    }

    /**
     * Get the configured Handle prefix string, or a default
     * @return configured prefix or "123456789"
     */
    public static String getPrefix()
    {
        String prefix = ConfigurationManager.getProperty("handle.prefix");
        if (null == prefix)
        {
            prefix = EXAMPLE_PREFIX; // XXX no good way to exit cleanly
            log.error("handle.prefix is not configured; using " + prefix);
        }
        return prefix;
    }

    protected static String getCanonicalForm(String handle)
    {

        // Let the admin define a new prefix, if not then we'll use the
        // CNRI default. This allows the admin to use "hdl:" if they want to or
        // use a locally branded prefix handle.myuni.edu.
        String handlePrefix = ConfigurationManager.getProperty("handle.canonical.prefix");
        if (handlePrefix == null || handlePrefix.length() == 0)
        {
            handlePrefix = "http://hdl.handle.net/";
        }

        return handlePrefix + handle;
    }

    protected String createNewIdentifier(Context context, DSpaceObject dso, String handleId) throws SQLException {
        if(handleId == null)
        {
            return handleService.createHandle(context, dso);
        }else{
            return handleService.createHandle(context,  dso, handleId);
        }
    }

    protected String makeIdentifierBasedOnHistory(Context context, DSpaceObject dso, String handleId, VersionHistory history) throws AuthorizeException, SQLException
    {
        Item item = (Item)dso;

        // FIRST time a VERSION is created 2 identifiers will be minted  and the canonical will be updated to point to the newer URL:
        //  - id.1-->old URL
        //  - id.2-->new URL
        Version version = versionService.getVersion(context, item);
        Version previous = versionHistoryService.getPrevious(history, version);
        String canonical = getCanonical(context, previous.getItem());
        if (versionHistoryService.isFirstVersion(history, previous))
        {
            // add a new Identifier for previous item: 12345/100.1
            String identifierPreviousItem=canonical + DOT + previous.getVersionNumber();
            //Make sure that this hasn't happened already
            if(handleService.resolveToObject(context, identifierPreviousItem) == null)
            {
                handleService.createHandle(context, previous.getItem(), identifierPreviousItem, true);
            }
        }


        // add a new Identifier for this item: 12345/100.x
        String idNew = canonical + DOT + version.getVersionNumber();
        //Make sure we don't have an old handle hanging around (if our previous version was deleted in the workspace)
        if(handleService.resolveToObject(context, idNew) == null)
        {
            handleService.createHandle(context, dso, idNew);
        }else{
            handleService.modifyHandleDSpaceObject(context, idNew, dso);

        }

        return handleId;
    }


    protected String getCanonical(Context context, Item item) throws SQLException {
        String canonical = item.getHandle();
        if( canonical.matches(".*/.*\\.\\d+") && canonical.lastIndexOf(DOT)!=-1)
        {
            canonical =  canonical.substring(0, canonical.lastIndexOf(DOT));
        }

        return canonical;
    }

    protected String getCanonical(String identifier)
    {
        String canonical = identifier;
        if( canonical.matches(".*/.*\\.\\d+") && canonical.lastIndexOf(DOT)!=-1)
        {
            canonical =  canonical.substring(0, canonical.lastIndexOf(DOT));
        }

        return canonical;
    }


    protected void populateHandleMetadata(Context context, Item item)
            throws SQLException, IOException, AuthorizeException
    {
        String handleref = getCanonicalForm(getCanonical(context, item));

        // Add handle as identifier.uri DC value.
        // First check that identifier doesn't already exist.
        boolean identifierExists = false;
        List<MetadataValue> identifiers = itemService.getMetadata(item, MetadataSchema.DC_SCHEMA, "identifier", "uri", Item.ANY);
        for (MetadataValue identifier : identifiers)
        {
            if (handleref.equals(identifier.getValue()))
            {
                identifierExists = true;
            }
        }
        if (!identifierExists)
        {
            itemService.addMetadata(context, item, MetadataSchema.DC_SCHEMA, "identifier", "uri", null, handleref);
        }
    }

    protected void modifyHandleMetadata(Context context, Item item, String handle)
            throws SQLException, IOException, AuthorizeException
    {
        String handleref = getCanonicalForm(handle);
        itemService.clearMetadata(context, item, MetadataSchema.DC_SCHEMA, "identifier", "uri", Item.ANY);
        itemService.addMetadata(context, item, MetadataSchema.DC_SCHEMA, "identifier", "uri", null, handleref);
        itemService.update(context, item);
    }
}

/*
 * The Fascinator - Plugin - Transformer - Handles
 * Copyright (C) 2011 University of Southern Queensland
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation; either version 2 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License along
 * with this program; if not, write to the Free Software Foundation, Inc.,
 * 51 Franklin Street, Fifth Floor, Boston, MA 02110-1301 USA.
 */
package au.edu.usq.fascinator.redbox.plugins.handle;

import au.edu.usq.fascinator.api.PluginDescription;
import au.edu.usq.fascinator.api.PluginException;
import au.edu.usq.fascinator.api.PluginManager;
import au.edu.usq.fascinator.api.storage.DigitalObject;
import au.edu.usq.fascinator.api.storage.Storage;
import au.edu.usq.fascinator.api.transformer.Transformer;
import au.edu.usq.fascinator.api.transformer.TransformerException;
import au.edu.usq.fascinator.common.JsonSimpleConfig;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.security.PrivateKey;
import net.handle.hdllib.AbstractMessage;

import net.handle.hdllib.AbstractResponse;
import net.handle.hdllib.AdminRecord;
import net.handle.hdllib.CreateHandleRequest;
import net.handle.hdllib.Encoder;
import net.handle.hdllib.ErrorResponse;
import net.handle.hdllib.GenericResponse;
import net.handle.hdllib.HandleException;
import net.handle.hdllib.HandleResolver;
import net.handle.hdllib.HandleValue;
import net.handle.hdllib.PublicKeyAuthenticationInfo;
import net.handle.hdllib.Util;

import org.apache.commons.io.IOUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <p>
 * Create and store handles against a particular name authority. Most of the
 * handle code is a direct implementation of the examples provided at:
 * http://www.handle.net/
 * </p>
 *
 * <p>
 * Download the system and it will be bundled with all source, including samples
 * on handle creation: src.java/net/handle/apps/simple/HDLCreate.java
 * </p>
 *
 * @author Greg Pendlebury
 */
public class HandleTransformer implements Transformer {

    /** Static values used during handle creation */
    private static int ADMIN_INDEX = 100;
    private static int PUBLIC_INDEX = 300;
    private static String ADMIN_TYPE = "HS_ADMIN";
    private static String DESC_TYPE = "DESC";

    /** Logging **/
    private static Logger log = LoggerFactory
            .getLogger(HandleTransformer.class);

    /** Configuration */
    private JsonSimpleConfig config;
    private JsonSimpleConfig itemConfig;

    /** Storage layer */
    private Storage storage;

    /** Handle Resolver */
    private HandleResolver resolver;

    /** Keyed authentication data */
    private PublicKeyAuthenticationInfo authentication;

    /** Administrative Record */
    private AdminRecord admin;

    /**
     * Constructor
     */
    public HandleTransformer() {
    }

    /**
     * Init method from file
     *
     * @param jsonFile
     * @throws IOException
     * @throws PluginException
     */
    @Override
    public void init(File jsonFile) throws PluginException {
        try {
            config = new JsonSimpleConfig(jsonFile);
            reset();
        } catch (IOException e) {
            throw new PluginException("Error reading config: ", e);
        }
    }

    /**
     * Init method from String
     *
     * @param jsonString
     * @throws IOException
     * @throws PluginException
     */
    @Override
    public void init(String jsonString) throws PluginException {
        try {
            config = new JsonSimpleConfig(jsonString);
            reset();
        } catch (IOException e) {
            throw new PluginException("Error reading config: ", e);
        }
    }

    /**
     * Reset the transformer in preparation for a new object
     */
    private void reset() throws TransformerException {
        // First time execution, prepare storage
        if (storage == null) {
            try {
                String storageType = config.getString(null, "storage", "type");
                storage = PluginManager.getStorage(storageType);
                storage.init(JsonSimpleConfig.getSystemFile());
            } catch (Exception ex) {
                throw new TransformerException(ex);
            }
        }

        // First time execution of some of the Handle details
        if (resolver == null) {
            // Do we have a naming authority? No need to evaluate the
            //  complicated stuff if we don't have this
            String namingAuthority = config.getString(null,
                    "transformerDefaults", "handle", "namingAuthority");
            if (namingAuthority == null || namingAuthority.equals("")) {
                throw new TransformerException(
                        "No naming authority specified!");
            }
            // The methods below want the data as a byte array
            byte[] prefix = null;
            try {
                prefix = namingAuthority.getBytes("UTF8");
            } catch(Exception ex) {
                throw new TransformerException(
                        "Error reading naming authority: ", ex);
            }

            // Our basic resolver... processes requests when they are ready
            resolver = new HandleResolver();
            resolver.traceMessages = true;

            // Private key
            PrivateKey privateKey = null;
            try {
                byte[] key = readPrivateKey();
                byte[] passPhrase = readPassPhrase(key);
                key = Util.decrypt(key, passPhrase);
                privateKey = Util.getPrivateKeyFromBytes(key, 0);
            } catch(Exception ex) {
                throw new TransformerException(
                        "Error during key resolution: ", ex);
            }

            // Create our authentication object for this naming authority
            authentication = new PublicKeyAuthenticationInfo(prefix,
                    PUBLIC_INDEX, privateKey);

            // Set up an administrative record, used to stamp admin rights
            //  on new handles. All those 'true' flags give us full access
            admin = new AdminRecord(prefix, PUBLIC_INDEX,
                    true, true, true, true, true, true,
                    true, true, true, true, true, true);
        }

        // Each execution, check config for this item
    }

    /**
     * Create a HandleValue object holding a public description for the handle
     *
     * @param description: The description to use
     * @return HandleValue: The instantiated value, NULL if errors occurred.
     */
    private HandleValue getDescHandleValue(String description) {
        byte[] type = null;
        byte[] descBytes = null;
        try {
            type = DESC_TYPE.getBytes("UTF8");
            descBytes = description.getBytes("UTF8");
        } catch (Exception ex) {
            log.error("Error creating description handle value: ", ex);
            return null;
        }

        return createHandleValue(PUBLIC_INDEX, type, descBytes);
    }

    /**
     * Create a HandleValue object holding admin data to govern the handle
     *
     * @return HandleValue: The instantiated value, NULL if errors occurred.
     */
    private HandleValue getAdminHandleValue() {
        byte[] type = null;
        try {
            type = ADMIN_TYPE.getBytes("UTF8");
        } catch (Exception ex) {
            // This shouldn't occur, given that ADMIN_TYPE is static, but
            //  we'll return a null response if it ever does;
            log.error("Error creating admin handle value: ", ex);
            return null;
        }

        return createHandleValue(ADMIN_INDEX, type,
                Encoder.encodeAdminRecord(admin));
    }

    /**
     * Create a HandleValue using the index, type and value provided.
     *
     * @param index: The index to assign the value
     * @param type: The type of this value
     * @param value: The data to load into this value
     * @return HandleValue: The instantiated value
     */
    private HandleValue createHandleValue(int index, byte[] type, byte[] value) {
        return new HandleValue(index, type, value,
                // You shouldn't need to change any of this,
                //  see handle.net examples for details.
                HandleValue.TTL_TYPE_RELATIVE, 86400,
                now(), null,
                // Security, all rights except 'public write'
                true, true, true, false);
    }

    /**
     * Trivial wrapper to resolve the current time to an integer
     *
     * @return int: The time now as an integer
     */
    private int now() {
        return (int) (System.currentTimeMillis() / 1000);
    }

    /**
     * Reads a private key from the configured location and returns
     * in a byte array
     *
     * @return byte[]: The byte data of the private key
     * @throws TransformerException: If the key is not found or inaccessible
     */
    private byte[] readPrivateKey() throws TransformerException {
        // Make sure it's configured
        String keyPath = config.getString(null,
                "transformerDefaults", "handle", "privateKeyPath");
        if (keyPath == null) {
            throw new TransformerException("No private key provided!");
        }

        // Retrieve it
        try {
            // Access the file
            File file = new File(keyPath);
            if (file == null || !file.exists()) {
                throw new TransformerException(
                        "The private key file does not exist or cannot" +
                        " be found: '" + keyPath + "'");
            }
            FileInputStream stream = new FileInputStream(file);

            // Stream the file into a byte array
            return IOUtils.toByteArray(stream);
        } catch (Exception ex) {
            throw new TransformerException("Error accessing file: ", ex);
        }
    }

    /**
     * <p>
     * Confirms that the provided private key actually requires a pass phrase
     * and looks for this in configuration. If not found (but required) an
     * error will be logged, but a null value will be returned. This is a
     * mis-configuration.
     * </p>
     *
     * <p>
     * Using the key will fail in this case and should be appropriately caught,
     * but this method will only through an exception if the configuration is
     * correct, there was just an error during retrieval.
     * </p>
     *
     * @param key: The private keey to check
     * @return byte[]: The byte data of the pass phrase, possibly null
     * @throws TransformerException: If the key is not inaccessible
     */
    private byte[] readPassPhrase(byte[] key) throws TransformerException {
        try {
            if (Util.requiresSecretKey(key)) {
                String password = config.getString(null,
                        "transformerDefaults", "handle", "passPhrase");
                if (password == null) {
                    log.error("The private key requires a pass phrase" +
                            " and none was provided!");
                }
            }
        } catch(Exception ex) {
            throw new TransformerException("Error during key resolution: ", ex);
        }

        // Null is fine if no passphrase is required
        return null;
    }

    /**
     * Transform method
     *
     * @param object : DigitalObject to be transformed
     * @param jsonConfig : String containing configuration for this item
     * @return DigitalObject The object after being transformed
     * @throws TransformerException
     */
    @Override
    public DigitalObject transform(DigitalObject in, String jsonConfig)
            throws TransformerException {
        // Read item config and reset before we start
        try {
            itemConfig = new JsonSimpleConfig(jsonConfig);
        } catch (IOException ex) {
            throw new TransformerException(
                    "Error reading item configuration!", ex);
        }
        reset();

        return in;
    }

    /**
     * Create a handle for the specified description
     *
     * @param handle: The handle suffix desired
     * @param description: The description to allocate to the new handle
     * @return String: The newly created handle, NULL if the suffix is not free
     * @throws TransformerException: If any errors occur during the process
     */
    private String createHandle(String suffix, String description)
            throws TransformerException {
        // Make sure the suffix is even valid
        byte[] suffixBytes = null;
        try {
            suffixBytes = suffix.getBytes("UTF8");
        } catch (Exception ex) {
            throw new TransformerException(
                    "Invalid encoding for Suffix: '" + suffix + "'", ex);
        }

        // Prepare the data going to be used inside the handle
        HandleValue adminVal = getAdminHandleValue();
        HandleValue descVal = getDescHandleValue(description);
        if (adminVal == null || descVal == null) {
            throw new TransformerException("Error creating HandleValues!");
        }
        HandleValue[] values = {adminVal, descVal};

        // Now prepare the actualy creationg request for sending
        CreateHandleRequest req = new CreateHandleRequest(
                suffixBytes, values, authentication);

        // And send
        GenericResponse result = null;
        try {
            AbstractResponse response = resolver.processRequest(req);
            // Success case
            if (response.responseCode == AbstractMessage.RC_SUCCESS) {
                result = (GenericResponse) response;
            } else {
                // Failure case... but expected failure
                if (response.responseCode ==
                        AbstractMessage.RC_HANDLE_ALREADY_EXISTS) {
                    log.warn("Handle '{}' already in use", suffix);
                    return null;
                }

                // Failure case... unexpected cause
                if (response instanceof ErrorResponse) {
                    throw new TransformerException("Error creating handle: " +
                            ((ErrorResponse) response).toString());
                } else {
                    throw new TransformerException("Unknown error during" +
                            " handle creation. The create API call has" +
                            " failed, but no error response was returned." +
                            " Message: '" +
                            AbstractMessage.getResponseCodeMessage(
                            response.responseCode) + "'");
                }
            }
        } catch (HandleException ex) {
            throw new TransformerException(
                    "Error attempting to create handle:", ex);
        }

        // TODO... lets hope this is the valid handle
        return result.toString();
    }

    /**
     * Get Transformer ID
     *
     * @return id
     */
    @Override
    public String getId() {
        return "handle";
    }

    /**
     * Get Transformer Name
     *
     * @return name
     */
    @Override
    public String getName() {
        return "Handle Transformer";
    }

    /**
     * Gets a PluginDescription object relating to this plugin.
     *
     * @return a PluginDescription
     */
    @Override
    public PluginDescription getPluginDetails() {
        return new PluginDescription(this);
    }

    /**
     * Shut down the transformer plugin
     */
    @Override
    public void shutdown() throws PluginException {
        if (storage != null) {
            storage.shutdown();
        }
    }
}

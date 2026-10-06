package org.ddlsmith.dbms;

import java.util.List;

import com.beust.jcommander.Parameter;
import com.beust.jcommander.Parameters;

import org.ddlsmith.DBMSSpecificOptions;
import org.ddlsmith.ddl.DDLFeature;

/**
 * The command line options of a database provider.
 *
 * <p>All providers share the same option object because they share the same connection parameters;
 * what differs between engines is the dialect, the introspector and the default JDBC URL, all of
 * which come from {@code config.json} or from the built-in configuration.</p>
 */
@Parameters(separators = "=", commandDescription = "Options of the database under test")
public class ConnectionOptions implements DBMSSpecificOptions<DDLFeature> {

    @Parameter(names = "--url", description = "An explicit JDBC URL, overriding config.json.")
    private String url;

    @Parameter(names = "--ddl-features", description = "Comma-separated list of extra DDL features to assume the engine supports.")
    private List<String> extraFeatures;

    @Parameter(names = "--disable-ddl-features", description = "Comma-separated list of DDL features to assume the engine does NOT support.")
    private List<String> disabledFeatures;

    public String getUrl() {
        return url;
    }

    public List<String> getExtraFeatures() {
        return extraFeatures;
    }

    public List<String> getDisabledFeatures() {
        return disabledFeatures;
    }

    @Override
    public List<DDLFeature> getTestOracleFactory() {
        return List.of();
    }
}

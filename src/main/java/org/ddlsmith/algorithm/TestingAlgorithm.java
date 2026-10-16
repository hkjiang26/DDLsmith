package org.ddlsmith.algorithm;

import java.util.List;

import org.ddlsmith.GlobalState;
import org.ddlsmith.oracle.OracleFailure;

/**
 * A testing algorithm drives one round of DDLsmith: it generates the ER diagram, maps it to a
 * relational schema, executes the DDL and runs the oracles.
 *
 * <p>Different algorithms stress different aspects: plain creation, schema evolution through
 * {@code ALTER TABLE}, constraint enforcement, or differential comparison across engines.</p>
 */
public interface TestingAlgorithm {

    String getName();

    /**
     * Runs one round and returns the failures that were found (empty when the round was clean).
     */
    List<OracleFailure> test(GlobalState<?, ?> state) throws Exception;
}

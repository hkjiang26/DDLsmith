package org.ddlsmith.common.schema;

import java.util.List;
import java.util.Objects;

/** A referential action of a foreign key. */
public enum ReferentialAction {
    NO_ACTION, RESTRICT, CASCADE, SET_NULL, SET_DEFAULT;

    public static ReferentialAction fromSql(String token) {
        if (token == null) {
            return NO_ACTION;
        }
        String normalized = token.trim().toUpperCase().replace(' ', '_');
        if (normalized.isEmpty() || normalized.equals("NO_ACTION")) {
            return NO_ACTION;
        }
        try {
            return valueOf(normalized);
        } catch (IllegalArgumentException e) {
            return NO_ACTION;
        }
    }

    public String toSql() {
        return name().replace('_', ' ');
    }
}

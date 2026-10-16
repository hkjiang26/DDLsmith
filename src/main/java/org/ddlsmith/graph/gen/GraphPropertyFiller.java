package org.ddlsmith.graph.gen;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.ddlsmith.Randomly;
import org.ddlsmith.common.schema.DataType;
import org.ddlsmith.graph.GraphEdge;
import org.ddlsmith.graph.GraphLabel;
import org.ddlsmith.graph.GraphModel;
import org.ddlsmith.graph.GraphNode;
import org.ddlsmith.graph.GraphProperty;
import org.ddlsmith.graph.GraphRelationshipType;
import org.ddlsmith.graph.GraphSchema;

/**
 * Fills the property slots of a generated graph with values of the declared type.
 *
 * <p>For every node and every edge, each slot of its label (respectively relationship type) is
 * filled with probability {@code presenceProbability}. An <em>absent key means the property is
 * null</em>, so nothing is ever stored as an explicit {@code null} value; mandatory slots
 * ({@code presenceProbability == 1.0}) are always present, which is what the ER mapper relies on
 * when it decides whether an entity has a key of its own.</p>
 *
 * <p>Values are not drawn independently per node: every slot owns a small pool of ~12 distinct
 * values that is filled on first use and then sampled with {@link Randomly#fromList(List)}. Values
 * therefore repeat across the nodes and edges of a graph, which is what makes later constraint
 * testing interesting (grouping, joins, uniqueness checks all see collisions). Pools are created
 * per {@link #fill(GraphModel, GraphSchema)} call, so the same seed always produces the same
 * values.</p>
 *
 * <p>Java representation of the canonical types: SMALLINT is a {@link Short}, INTEGER an
 * {@link Integer}, BIGINT a {@link Long}, DECIMAL/REAL/DOUBLE a {@link Double} (DECIMAL rounded to
 * two decimals), CHAR/VARCHAR/TEXT/JSON a {@link String}, BOOLEAN a {@link Boolean}, DATE a
 * {@link java.sql.Date}, TIME a {@link java.sql.Time}, TIMESTAMP a {@link java.sql.Timestamp},
 * BLOB a {@code byte[]}, UUID a {@link UUID}, ENUM a {@link String} from the type's value list and
 * ARRAY an {@code Object[]} of element values.</p>
 *
 * <p>The methods are static: {@code GraphPropertyFiller.fill(graph, schema)} can be called without
 * an instance.</p>
 */
public final class GraphPropertyFiller {

    private static final int POOL_SIZE = 12;
    private static final int LARGE_POOL_SIZE = 8;
    private static final int MIN_INTEGER = -1000;
    private static final int MAX_INTEGER = 1000;
    private static final double MIN_DECIMAL = -100;
    private static final double MAX_DECIMAL = 100;
    private static final double MIN_REAL = -1000;
    private static final double MAX_REAL = 1000;
    private static final int MAX_STRING_LENGTH = 10;
    private static final LocalDate FIRST_DAY = LocalDate.of(1970, 1, 1);
    private static final LocalDate LAST_DAY = LocalDate.of(2030, 12, 31);
    private static final long DAY_COUNT = ChronoUnit.DAYS.between(FIRST_DAY, LAST_DAY) + 1;
    private static final int SECONDS_PER_DAY = 24 * 60 * 60;

    private GraphPropertyFiller() {
    }

    /** Fills every node and edge property of {@code graph} according to {@code schema}. */
    public static void fill(GraphModel graph, GraphSchema schema) {
        Map<String, List<Object>> pools = new HashMap<>();
        for (GraphNode node : graph.getNodes()) {
            int labelIndex = node.getLabelIndex();
            if (labelIndex < 0 || labelIndex >= schema.getLabelCount()) {
                continue;
            }
            GraphLabel label = schema.getLabel(labelIndex);
            for (GraphProperty slot : label.getProperties()) {
                if (isPresent(slot)) {
                    node.putProperty(slot.getKey(), valueFor(slot, pools));
                }
            }
        }
        for (GraphEdge edge : graph.getEdges()) {
            int typeIndex = edge.getRelationshipTypeIndex();
            if (typeIndex < 0 || typeIndex >= schema.getRelationshipTypeCount()) {
                continue;
            }
            GraphRelationshipType type = schema.getRelationshipType(typeIndex);
            for (GraphProperty slot : type.getProperties()) {
                if (isPresent(slot)) {
                    edge.putProperty(slot.getKey(), valueFor(slot, pools));
                }
            }
        }
    }

    /**
     * @return true if the slot carries a value on this node/edge. Mandatory slots are always
     *         present; for the others {@link Rand#chance(double)} is used, which draws an exactly
     *         {@code presenceProbability}-distributed decision from the seeded generator.
     */
    private static boolean isPresent(GraphProperty slot) {
        return slot.isMandatory() || Rand.chance(slot.getPresenceProbability());
    }

    private static Object valueFor(GraphProperty slot, Map<String, List<Object>> pools) {
        DataType type = slot.getType();
        if (type.getCategory() == DataType.Category.UUID) {
            // fresh per node/edge: a repeated UUID would defeat the purpose of the type. Built from
            // the seeded generator rather than UUID.randomUUID() so that runs stay reproducible.
            return seededUuid();
        }
        String poolKey = slot.getKey() + "#" + type.getName();
        List<Object> pool = pools.computeIfAbsent(poolKey, key -> buildPool(type));
        return Randomly.fromList(pool);
    }

    /* ------------------------------------------------------------------ pools */

    private static List<Object> buildPool(DataType type) {
        if (type.getCategory() == DataType.Category.ENUM) {
            List<String> enumValues = type.getEnumValues();
            if (enumValues != null && !enumValues.isEmpty()) {
                return new ArrayList<>(enumValues);
            }
            return List.of(shortString());
        }
        int size = poolSize(type);
        Set<Object> distinct = new LinkedHashSet<>();
        int attempts = size * 4;
        for (int i = 0; i < attempts && distinct.size() < size; i++) {
            distinct.add(randomValue(type));
        }
        if (distinct.isEmpty()) {
            distinct.add(randomValue(type));
        }
        return new ArrayList<>(distinct);
    }

    private static int poolSize(DataType type) {
        switch (type.getCategory()) {
        case BOOLEAN:
            return 2;
        case BLOB:
        case JSON:
        case ARRAY:
            return LARGE_POOL_SIZE;
        default:
            return POOL_SIZE;
        }
    }

    /* ------------------------------------------------------------------ values */

    private static Object randomValue(DataType type) {
        switch (type.getCategory()) {
        case SMALLINT:
            return (short) Randomly.getInteger(MIN_INTEGER, MAX_INTEGER);
        case INTEGER:
            return Randomly.getInteger(MIN_INTEGER, MAX_INTEGER);
        case BIGINT:
            return (long) Randomly.getInteger(MIN_INTEGER, MAX_INTEGER);
        case DECIMAL:
            return roundToTwoDecimals(boundedDouble(MIN_DECIMAL, MAX_DECIMAL));
        case REAL:
        case DOUBLE:
            return boundedDouble(MIN_REAL, MAX_REAL);
        case CHAR:
        case VARCHAR:
        case TEXT:
            return shortString();
        case BOOLEAN:
            return Randomly.getBoolean();
        case DATE:
            return randomDate();
        case TIME:
            return randomTime();
        case TIMESTAMP:
        case TIMESTAMPTZ:
            return randomTimestamp();
        case BLOB:
            return randomBytes();
        case UUID:
            return seededUuid();
        case JSON:
            return randomJson();
        case ENUM:
            List<String> enumValues = type.getEnumValues();
            return enumValues == null || enumValues.isEmpty() ? shortString() : Randomly.fromList(enumValues);
        case ARRAY:
            return randomArray(type.getElementType());
        default:
            return shortString();
        }
    }

    /**
     * {@link Randomly#getDouble(double, double)} can overshoot its bounds: its value cache may
     * answer with a cached {@code long} (for example {@code 1234567.0}). The result is clamped so
     * that the produced values stay inside the documented range; repeated boundary values are
     * harmless, and in fact in the spirit of DDLsmith's constant caching.
     */
    private static double boundedDouble(double min, double max) {
        double value = Randomly.getDouble(min, max);
        if (value < min) {
            return min;
        }
        if (value > max) {
            return max;
        }
        return value;
    }

    private static double roundToTwoDecimals(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    private static String shortString() {
        String value = Randomly.getString();
        return value.length() <= MAX_STRING_LENGTH ? value : value.substring(0, MAX_STRING_LENGTH);
    }

    private static java.sql.Date randomDate() {
        return java.sql.Date.valueOf(FIRST_DAY.plusDays(Randomly.getLong(0, DAY_COUNT)));
    }

    private static java.sql.Time randomTime() {
        return java.sql.Time.valueOf(LocalTime.ofSecondOfDay(Randomly.getLong(0, SECONDS_PER_DAY)));
    }

    private static java.sql.Timestamp randomTimestamp() {
        LocalDate day = FIRST_DAY.plusDays(Randomly.getLong(0, DAY_COUNT));
        LocalTime time = LocalTime.ofSecondOfDay(Randomly.getLong(0, SECONDS_PER_DAY));
        return java.sql.Timestamp.valueOf(LocalDateTime.of(day, time));
    }

    private static byte[] randomBytes() {
        byte[] bytes = new byte[1 + Randomly.getInteger(8)];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) Randomly.getInteger(0, 256);
        }
        return bytes;
    }

    private static UUID seededUuid() {
        return new UUID(Randomly.getLong(), Randomly.getLong());
    }

    private static String randomJson() {
        return "{\"v\":" + Randomly.getInteger(MIN_INTEGER, MAX_INTEGER) + "}";
    }

    private static Object[] randomArray(DataType elementType) {
        int length = 1 + Randomly.getInteger(3);
        Object[] values = new Object[length];
        DataType element = elementType == null ? DataType.integer() : elementType;
        for (int i = 0; i < length; i++) {
            values[i] = randomValue(element);
        }
        return values;
    }
}

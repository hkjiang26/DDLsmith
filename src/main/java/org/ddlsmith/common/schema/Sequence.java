package org.ddlsmith.common.schema;

/** A standalone sequence object. */
public class Sequence {

    private String name;
    private String schemaName;
    private long start = 1;
    private long increment = 1;
    private Long minValue;
    private Long maxValue;
    private boolean cycle;

    public Sequence(String name) {
        this.name = name;
    }

    public String getName() {
        return name;
    }

    public Sequence setName(String name) {
        this.name = name;
        return this;
    }

    public String getSchemaName() {
        return schemaName;
    }

    public Sequence setSchemaName(String schemaName) {
        this.schemaName = schemaName;
        return this;
    }

    public long getStart() {
        return start;
    }

    public Sequence setStart(long start) {
        this.start = start;
        return this;
    }

    public long getIncrement() {
        return increment;
    }

    public Sequence setIncrement(long increment) {
        this.increment = increment;
        return this;
    }

    public Long getMinValue() {
        return minValue;
    }

    public Sequence setMinValue(Long minValue) {
        this.minValue = minValue;
        return this;
    }

    public Long getMaxValue() {
        return maxValue;
    }

    public Sequence setMaxValue(Long maxValue) {
        this.maxValue = maxValue;
        return this;
    }

    public boolean isCycle() {
        return cycle;
    }

    public Sequence setCycle(boolean cycle) {
        this.cycle = cycle;
        return this;
    }

    @Override
    public String toString() {
        return "SEQUENCE " + name;
    }
}

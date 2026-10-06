package org.ddlsmith.ermodel;

/**
 * One end of a relationship: which entity participates, in which role, with which cardinality and
 * participation constraint.
 *
 * <p>The cardinality is the number of instances of {@link #entityName} that pair with <em>one</em>
 * instance of the relationship — equivalently, with one instance of the other participants. So for
 * {@code A —1— R —N— B} the participant for {@code A} has {@link Cardinality#ONE} (one {@code A}
 * per {@code B}) and the participant for {@code B} has {@link Cardinality#MANY} (many {@code B} per
 * {@code A}). The foreign key of a 1:N relationship therefore belongs on the participant whose
 * cardinality is {@code MANY}, which is the classic "foreign key on the many side" rule.</p>
 *
 * <p>This matches {@link org.ddlsmith.graph.GraphRelationshipType}: the cardinality of a
 * relationship type's <em>from</em> side is the number of from-instances per one to-instance, which
 * is exactly the cardinality of the from-entity's participant.</p>
 */
public class ERParticipant {

    private String entityName;
    /** The role name, used to disambiguate recursive relationships. */
    private String roleName;
    /** The maximum number of instances of {@link #entityName} per relationship instance. */
    private Cardinality cardinality = Cardinality.MANY;
    /** Whether every instance of {@link #entityName} must participate. */
    private Participation participation = Participation.PARTIAL;

    public ERParticipant(String entityName) {
        this.entityName = entityName;
        this.roleName = entityName;
    }

    public ERParticipant(String entityName, String roleName) {
        this.entityName = entityName;
        this.roleName = roleName;
    }

    public String getEntityName() {
        return entityName;
    }

    public ERParticipant setEntityName(String entityName) {
        this.entityName = entityName;
        return this;
    }

    public String getRoleName() {
        return roleName;
    }

    public ERParticipant setRoleName(String roleName) {
        this.roleName = roleName;
        return this;
    }

    public Cardinality getCardinality() {
        return cardinality;
    }

    public ERParticipant setCardinality(Cardinality cardinality) {
        this.cardinality = cardinality;
        return this;
    }

    public Participation getParticipation() {
        return participation;
    }

    public ERParticipant setParticipation(Participation participation) {
        this.participation = participation;
        return this;
    }

    public int min() {
        return participation.min();
    }

    public int max() {
        return cardinality == Cardinality.ONE ? 1 : -1;
    }

    /** @return the classic (min,max) notation, e.g. {@code (0,N)} */
    public String toCardinalityString() {
        return "(" + min() + "," + (max() == -1 ? "N" : String.valueOf(max())) + ")";
    }

    @Override
    public String toString() {
        return entityName + (roleName.equals(entityName) ? "" : " AS " + roleName) + " " + toCardinalityString();
    }
}

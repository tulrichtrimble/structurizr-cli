package com.structurizr.cli.sync.backstage;

public class Relation {

    public String type;
    public String targetRef(){
        return target.kind.toLowerCase() + ":" + target.namespace + "/" + target.name ;
    }
    public RelationTarget target;

    /**
     * Parses a targetRef string into a RelationTarget object
     * Format expected: kind:namespace/name
     *
     * @param targetRef The target reference to parse
     * @return A RelationTarget object, or null if the format is invalid
     */
    public static RelationTarget parseTargetRef(String targetRef) {
        if (targetRef == null || targetRef.isEmpty()) {
            return null;
        }

        // Parse the targetRef which should be in the format kind:namespace/name
        int colonIndex = targetRef.indexOf(':');
        int slashIndex = targetRef.indexOf('/', colonIndex);

        if (colonIndex <= 0 || slashIndex <= colonIndex) {
            return null; // Invalid format
        }

        RelationTarget target = new RelationTarget();
        target.kind = targetRef.substring(0, colonIndex).toLowerCase();
        target.namespace = targetRef.substring(colonIndex + 1, slashIndex);
        target.name = targetRef.substring(slashIndex + 1);

        return target;
    }
}


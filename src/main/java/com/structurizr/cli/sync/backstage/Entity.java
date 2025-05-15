package com.structurizr.cli.sync.backstage;

import java.util.ArrayList;
import java.util.List;

public class Entity {
    public static final String BACKSTAGE_ENTITY_KIND_DOMAIN = "Domain";
    public static final String BACKSTAGE_ENTITY_KIND_SYSTEM = "System";
    public static final String BACKSTAGE_ENTITY_KIND_COMPONENT = "Component";
    public static final String BACKSTAGE_ENTITY_KIND_RESOURCE = "Resource";
    public static final String BACKSTAGE_ENTITY_KIND_OWNER = "Owner";

    public String kind;
    public EntityMetadata metadata;
    public EntitySpec spec;
    public List<Relation> relations;

    public Entity() {
        this.relations = new ArrayList<>();
    }

    public Boolean addRelation(Relation relation){
        if (relation == null){
            return false;
        }
        relations.add(relation);
        return true;
    }

    public String toBackstageRef(){
        return Entity.toBackstageRef(kind, metadata.name, metadata.namespace);
    }

    public static String toBackstageRef(String kind, String name, String namespace) {
        if (namespace == null || namespace.isEmpty()){
            namespace = "default";
        }
        return kind.toLowerCase() + ":" + namespace + "/" + name;
    }

    /**
     * Parses a fully-qualified string into its segments
     * Format expected: kind:namespace/name
     *
     * @param refString The target reference to parse
     * @return A RelationTarget object, or null if the format is invalid
     */
    public static EntityRef parseTargetRef(String refString, String kind) throws Exception {
        if (refString == null || refString.isEmpty()) {
            return null;
        }

        if (!refString.contains(":")) {
            if (kind == null || kind.isEmpty()) {
                throw new Exception("Handling of refs without knowing the kind is not yet handled: " + refString);
            }
            else{
                // Not terribly efficient, but takes centralizes default domain assignment
                refString = toBackstageRef(kind, refString, "");
            }
        }

        // Parse the targetRef which should be in the format kind:namespace/name
        int colonIndex = refString.indexOf(':');
        int slashIndex = refString.indexOf('/', colonIndex);

        if (colonIndex <= 0 || slashIndex <= colonIndex) {
            return null; // Invalid format
        }

        EntityRef ref = new EntityRef();
        ref.kind = refString.substring(0, colonIndex).toLowerCase();
        ref.namespace = refString.substring(colonIndex + 1, slashIndex);
        ref.name = refString.substring(slashIndex + 1);

        return ref;
    }
}
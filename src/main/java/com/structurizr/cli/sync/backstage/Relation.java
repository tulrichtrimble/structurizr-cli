package com.structurizr.cli.sync.backstage;

public class Relation {

    public static final String BACKSTAGE_RELATION_TYPE_SUB_COMPONENT_OF = "subComponentOf";
    public static final String BACKSTAGE_RELATION_TYPE_PART_OF = "partOf";
    public static final String BACKSTAGE_RELATION_TYPE_DEPENDS_ON = "dependsOn";
    public static final String BACKSTAGE_RELATION_TYPE_CONSUMES_API = "consumesApi";
    public static final String BACKSTAGE_RELATION_TYPE_OWNED_BY = "ownedBy";

    public Relation(String typeParm, EntityRef targetParm){
        type = typeParm;
        target = targetParm;
    }
    public String type;
    public EntityRef target;
    public String toTargetRef(){
        return Entity.toBackstageRef(target.kind, target.name, target.namespace);
    }
}


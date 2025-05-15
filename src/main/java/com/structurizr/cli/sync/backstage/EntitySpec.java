package com.structurizr.cli.sync.backstage;

public class EntitySpec {

    public String owner;
    public String domain;
    public String system;
    public String type;
    public String subdomainOf;
    public String[] consumesApis;
    public String[] dependsOn;
    public String subcomponentOf;
    public String lifecycle;

    public Relation ownerRef() throws Exception {
        EntityRef targetRef =  Entity.parseTargetRef(owner, Entity.BACKSTAGE_ENTITY_KIND_OWNER);
        if (targetRef == null){
            return null;
        }
        return new Relation(Relation.BACKSTAGE_RELATION_TYPE_OWNED_BY, targetRef);
    }
    public Relation domainRef() throws Exception {
        EntityRef targetRef =  Entity.parseTargetRef(domain,  Entity.BACKSTAGE_ENTITY_KIND_DOMAIN);
        if (targetRef == null){
            return null;
        }
        return new Relation(Relation.BACKSTAGE_RELATION_TYPE_PART_OF, targetRef);
    }
    public Relation subdomainOfRef() throws Exception {
        EntityRef targetRef =  Entity.parseTargetRef(subdomainOf, Entity.BACKSTAGE_ENTITY_KIND_DOMAIN);
        if (targetRef == null){
            return null;
        }
        return new Relation(Relation.BACKSTAGE_RELATION_TYPE_PART_OF, targetRef);
    }
    public Relation systemRef() throws Exception {
        EntityRef targetRef =  Entity.parseTargetRef(system, Entity.BACKSTAGE_ENTITY_KIND_SYSTEM);
        if (targetRef == null){
            return null;
        }
        return new Relation(Relation.BACKSTAGE_RELATION_TYPE_PART_OF, targetRef);
    }
    public Relation subcomponentOfRef() throws Exception {
        EntityRef targetRef =  Entity.parseTargetRef(subcomponentOf, Entity.BACKSTAGE_ENTITY_KIND_COMPONENT);
        if (targetRef == null){
            return null;
        }
        return new Relation(Relation.BACKSTAGE_RELATION_TYPE_SUB_COMPONENT_OF, targetRef);
    }
    public Relation[] consumesApisRefs() throws Exception {
        if (consumesApis == null || consumesApis.length == 0) {
            return new Relation[0];
        }

        Relation[] refs = new Relation[consumesApis.length];
        for (int i = 0; i < consumesApis.length; i++) {
            EntityRef targetRef = Entity.parseTargetRef(dependsOn[i], Entity.BACKSTAGE_ENTITY_KIND_COMPONENT);
            refs[i] = new Relation(Relation.BACKSTAGE_RELATION_TYPE_CONSUMES_API, targetRef);
        }
        return refs;
    }
    public Relation[] dependsOnRefs() throws Exception {
        if (dependsOn == null || dependsOn.length == 0) {
            return new Relation[0];
        }

        Relation[] refs = new Relation[dependsOn.length];
        for (int i = 0; i < dependsOn.length; i++) {
            EntityRef targetRef = Entity.parseTargetRef(dependsOn[i], Entity.BACKSTAGE_ENTITY_KIND_COMPONENT);
                    refs[i] = new Relation(Relation.BACKSTAGE_RELATION_TYPE_DEPENDS_ON, targetRef);
        }
        return refs;
    }
}
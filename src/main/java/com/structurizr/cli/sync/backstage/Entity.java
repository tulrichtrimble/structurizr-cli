package com.structurizr.cli.sync.backstage;

public class Entity {

    public String kind;
    public EntityMetadata metadata;
    public EntitySpec spec;
    public Relation[] relations;

    public String toBackstageRef() {
        return kind.toLowerCase() + ":" + metadata.namespace + "/" + metadata.name;
    }

}
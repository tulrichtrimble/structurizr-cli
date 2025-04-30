package com.structurizr.cli.sync.backstage;

import java.util.ArrayList;
import java.util.List;

public class Entity {

    public String kind;
    public EntityMetadata metadata;
    public EntitySpec spec;
    public List<Relation> relations;

    public Entity() {
        this.relations = new ArrayList<>();
    }

    public String toBackstageRef() {
        return kind.toLowerCase() + ":" + metadata.namespace + "/" + metadata.name;
    }
}
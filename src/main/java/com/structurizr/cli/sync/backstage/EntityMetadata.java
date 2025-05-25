package com.structurizr.cli.sync.backstage;

import java.util.Collection;
import java.util.Map;

public class EntityMetadata {

    public String namespace;
    public String name;
    public String description;
    public Collection<String> tags;
    public Map<String, String> annotations;

}
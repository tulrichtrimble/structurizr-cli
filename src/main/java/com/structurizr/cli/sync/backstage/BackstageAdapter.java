package com.structurizr.cli.sync.backstage;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.structurizr.Workspace;
import com.structurizr.configuration.WorkspaceScope;
import com.structurizr.model.Container;
import com.structurizr.model.SoftwareSystem;
import com.structurizr.util.StringUtils;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

public class BackstageAdapter {

    public static final String LANDSCAPE_WORKSPACE_NAME = "Landscape";
    public static final String BACKSTAGE_ENTITY_KIND_DOMAIN = "Domain";
    public static final String BACKSTAGE_ENTITY_KIND_SYSTEM = "System";
    public static final String BACKSTAGE_ENTITY_KIND_COMPONENT = "Component";
    public static final String BACKSTAGE_ENTITY_KIND_RESOURCE = "Resource";
    public static final String BACKSTAGE_RELATION_TYPE_HAS_PART = "hasPart";
    public static final String BACKSTAGE_RELATION_TYPE_DEPENDS_ON = "dependsOn";
    public static final String BACKSTAGE_RELATION_TYPE_CONSUMES_API = "consumesApi";
    public static final String BACKSTAGE_REF_PROPERTY_NAME = "backstage.ref";
    public static final String BACKSTAGE_SYSTEM_NAME = "backstage-system";

    // protected static String ExpandGroups(Entity[] entities, String innerDomain){
    //     Entity domain = null;
    //     String domainPath="";
    //     for (Entity entity : entities) {
    //         if (BACKSTAGE_ENTITY_KIND_DOMAIN.equals(entity.kind)) {
    //             if (entity.metadata.name.equals(innerDomain)){
    //                 domain = entity;
    //                 domainPath = entity.metadata.name;
    //                 break;
    //             }
    //         }
    //     }

    //     if (domain == null) {
    //         return "";
    //     }

    //     if (domain.spec.subdomainOf != null) {
    //         domainPath = ExpandGroups(entities, domain.spec.subdomainOf) + "/" + domainPath;
    //     }
        
    //     return domainPath;
    // }


    public Entity[] getEntitiesFromBackstage(String location) throws Exception {
        if (StringUtils.isNullOrEmpty(location)){
            return null;
        }

        String content = "";
        if (location.startsWith("http")){
            try {
                HttpRequest request = HttpRequest.newBuilder(new URI(location)).build();
                HttpResponse<String> response = HttpClient.newBuilder()
                        .build()
                        .send(request, HttpResponse.BodyHandlers.ofString());

                content = response.body();
            }
            catch (Exception ex){
                System.out.println("Error connecting to " + location + " - Are you on the network/VPN?" + ex.getMessage());
                return null;
            }
        }
        else{
            content = Files.readString(new File(location).toPath());
        }

        boolean isYaml = isYamlContent(content) || 
                         (location.toLowerCase().endsWith(".yaml") || 
                          location.toLowerCase().endsWith(".yml"));

        if (isYaml) {
            return parseYamlEntities(content);
        } else {
            return parseJsonEntities(content);
        }
    }

    private boolean isYamlContent(String content) {
        // Simple heuristic: YAML often starts with --- or has key: value patterns
        // without the typical JSON brackets at the beginning
        String trimmed = content.trim();
        return trimmed.startsWith("---") || 
               (!trimmed.startsWith("[") && !trimmed.startsWith("{") && 
                Pattern.compile("^\\w+:\\s+\\S+", Pattern.MULTILINE).matcher(trimmed).find());
    }

    private Entity[] parseJsonEntities(String json) throws IOException {
        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        return objectMapper.readValue(json, Entity[].class);
    }

    private Entity[] parseYamlEntities(String yaml) throws IOException {
        ObjectMapper yamlMapper = new ObjectMapper(new YAMLFactory());
        yamlMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
        
        // YAML catalog files typically contain multiple documents separated by '---'
        // Each document represents a single entity
        List<Entity> entities = new ArrayList<>();
        
        // Split the YAML by document separator
        String[] documents = yaml.split("---");
        
        for (String document : documents) {
            if (!document.trim().isEmpty()) {
                Entity entity = yamlMapper.readValue(document.trim(), Entity.class);
                
                // Ensure metadata.namespace is set if not in YAML
                if (entity.metadata != null && entity.metadata.namespace == null) {
                    entity.metadata.namespace = "default";
                }
                
                // Build relations array from spec properties if missing
                if ((entity.relations == null || entity.relations.length == 0) && entity.spec != null) {
                    List<Relation> relations = new ArrayList<>();
                    
                    // Add owner relation
                    if (entity.spec.owner != null) {
                        addRelationFromRef(relations, "ownedBy", entity.spec.owner);
                    }
                    
                    // Add domain relation
                    if (entity.spec.domain != null) {
                        addRelationFromRef(relations, "partOf", entity.spec.domain);
                    }
                    
                    // Add system relation
                    if (entity.spec.system != null) {
                        addRelationFromRef(relations, "partOf", entity.spec.system);
                    }
                    
                    // Add subdomainOf relation
                    if (entity.spec.subdomainOf != null) {
                        addRelationFromRef(relations, "partOf", entity.spec.subdomainOf);
                    }
                    
                    // Set relations array
                    if (!relations.isEmpty()) {
                        entity.relations = relations.toArray(new Relation[0]);
                    }
                }
                
                entities.add(entity);
            }
        }
        
        return entities.toArray(new Entity[0]);
    }

    private void addRelationFromRef(List<Relation> relations, String relationType, String ref) {
        String targetRef = ref;
        
        // If the ref doesn't include the entity type prefix, infer it based on relation type
        if (!ref.contains(":")) {
            if (relationType.equals("ownedBy")) {
                targetRef = "group:default/" + ref;
            } else if (relationType.equals("partOf") && ref.equals("system")) {
                targetRef = "system:default/" + ref;
            } else if (relationType.equals("partOf") && ref.equals("domain")) {
                targetRef = "domain:default/" + ref;
            }
        }
        
        Relation relation = new Relation();
        relation.type = relationType;
        relation.targetRef = targetRef;
        
        relations.add(relation);
    }

    protected String toBackstageRef(Entity entity) {
        return entity.kind.toLowerCase() + ":" + entity.metadata.namespace + "/" + entity.metadata.name;
    }
}
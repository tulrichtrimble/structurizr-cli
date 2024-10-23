package com.structurizr.cli.sync.backstage;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import java.util.Arrays;

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


    public Entity[] getEntitiesFromBackstage(String localEntityFile) throws Exception {
        String json = "";
        // try {
        //     // loads the data from the Backstage demo instance
        //     String url = "https://backstage.dev.trimble.tools/api/catalog/entities"; // "https://demo.backstage.io/api/catalog/entities"; 
        //     HttpRequest request = HttpRequest.newBuilder(new URI(url)).build();
        //     HttpResponse<String> response = HttpClient.newBuilder()
        //             .build()
        //             .send(request, HttpResponse.BodyHandlers.ofString());

        //     json = response.body();
        // } catch (Exception e) {
            // if this doesn't work, we'll use a snapshot of the demo data instead
            if (localEntityFile != null) {
                json = Files.readString(new File(localEntityFile).toPath());
            }
        //}

        ObjectMapper objectMapper = new ObjectMapper();
        objectMapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

        return objectMapper.readValue(json, Entity[].class);
    }

    protected String toBackstageRef(Entity entity) {
        return entity.kind.toLowerCase() + ":" + entity.metadata.namespace + "/" + entity.metadata.name;
    }
}
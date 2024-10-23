package com.structurizr.cli.sync;

import com.structurizr.Workspace;
import com.structurizr.api.StructurizrClientException;
import com.structurizr.cli.AbstractCommand;
import com.structurizr.cli.sync.backstage.BackstageAdapter;
import com.structurizr.cli.sync.backstage.Entity;
import com.structurizr.cli.sync.backstage.Relation;
import com.structurizr.configuration.WorkspaceScope;
import com.structurizr.model.Container;
import com.structurizr.model.Element;
import com.structurizr.model.Relationship;
import com.structurizr.model.SoftwareSystem;
import com.structurizr.util.StringUtils;

import java.net.URI;
import java.util.Arrays;
import java.util.Date;
import java.util.Set;

public class SyncCommand extends AbstractCommand {
    @Override
    public void run(String... args) throws Exception , StructurizrClientException {
        ApiConnection apiConnection = new ApiConnection(
                "https://arch-repo-fahxgzhxbqgbdmgt.centralus-01.azurewebsites.net",
                //"http://localhost:8098",
                "TYLER_API_KEY",
                "$2a$10$NTb3eA0iuCucj3hYeZpmR.AWh3qrBHT8XB2iv37Bp5E/uREG.eQFS");

        StructurizrAdapter structurizrAdapter = new StructurizrAdapter(apiConnection);
        BackstageAdapter backstage = new BackstageAdapter();
        String backupEntityPath = System.getProperty("user.dir") + "\\repository\\landscape\\sync\\src\\resources\\backstage-trimble-entities.json";
        String extendedDslTemplate = System.getProperty("user.dir") + "\\repository\\landscape\\sync\\src\\resources\\TokenizedWorkspace.dsl";

        structurizrAdapter.PullWorkspaces();

        // load entities from demo.backstage.io
        Entity[] entities = backstage.getEntitiesFromBackstage(backupEntityPath);
        BuildCatalogWorkspaces(entities, structurizrAdapter);

        String namedWorkspaces = System.getProperty("user.dir") + "\\repository\\landscape\\structurizr\\named-workspaces";
        URI structurizrHost = new URI(apiConnection.url);
        structurizrAdapter.SaveWorkspacesLocal(namedWorkspaces, structurizrHost, extendedDslTemplate);
    }

    private static void BuildCatalogWorkspaces(Entity[] entities, StructurizrAdapter structurizrAdapter) throws Exception, StructurizrClientException {
        Workspace catalogSystemLandscapeWorkspace = structurizrAdapter.CreateShellWorkspace(StructurizrAdapter.LANDSCAPE_WORKSPACE_NAME, "The system landscape, imported from Trimble Backstage", WorkspaceScope.Landscape);
        structurizrAdapter.RegisterCatalogWorkspace(catalogSystemLandscapeWorkspace);

        // Create Systems
        for (Entity entity : entities) {
            if (BackstageAdapter.BACKSTAGE_ENTITY_KIND_SYSTEM.equals(entity.kind)) {
                String name = entity.metadata.name;
                Workspace catalogWorkspace = structurizrAdapter.GetCatalogWorkspace(name);
                if (catalogWorkspace == null) {
                    catalogWorkspace = structurizrAdapter.CreateShellWorkspace(name, entity.metadata.description, WorkspaceScope.SoftwareSystem);
                    // Prepare the entity to get a relationship set in Backstage
                    SoftwareSystem softwareSystem = catalogWorkspace.getModel().getSoftwareSystemWithName(name);
                    softwareSystem.addProperty(BackstageAdapter.BACKSTAGE_REF_PROPERTY_NAME, entity.toBackstageRef());
                    softwareSystem.addProperty(StructurizrAdapter.STRUCTURIZR_DSL_IDENTIFIER_PROPERTY_NAME, entity.metadata.name.replaceAll("\\W", ""));
                    structurizrAdapter.RegisterCatalogWorkspace(catalogWorkspace);
                }

                SoftwareSystem softwareSystem = catalogWorkspace.getModel().getSoftwareSystemWithName(name);
                Set<String> existingTags = softwareSystem.getTagsAsSet();
                existingTags.addAll(entity.metadata.tags);
                softwareSystem.addTags(existingTags.toArray(new String[0]));
            }
        }

        // Add Containers
        for (Entity entity : entities) {
            if (BackstageAdapter.BACKSTAGE_ENTITY_KIND_COMPONENT.equals(entity.kind) || BackstageAdapter.BACKSTAGE_ENTITY_KIND_RESOURCE.equals(entity.kind)) {
                if (!StringUtils.isNullOrEmpty(entity.spec.system)) {
                    String softwareSystemName = entity.spec.system;
                    Workspace catalogWorkspace = structurizrAdapter.GetCatalogWorkspace(softwareSystemName);
                    if (catalogWorkspace == null) {
                        throw new Exception("No workspace found for software system: " + softwareSystemName);
                    }
                    SoftwareSystem softwareSystem = catalogWorkspace.getModel().getSoftwareSystemWithName(softwareSystemName);
                    if (softwareSystem != null) {
                        String dslIdentifier = softwareSystem.getProperties().get(StructurizrAdapter.STRUCTURIZR_DSL_IDENTIFIER_PROPERTY_NAME) + "." + entity.metadata.name.replaceAll("\\W", "");

                        Container container = softwareSystem.getContainerWithName(entity.metadata.name);
                        if (container == null) {
                            container = softwareSystem.addContainer(entity.metadata.name);
                            catalogWorkspace.setLastModifiedDate(new Date());
                            container.setDescription(entity.metadata.description);
                            container.addProperty(StructurizrAdapter.STRUCTURIZR_DSL_IDENTIFIER_PROPERTY_NAME, dslIdentifier);
                            container.addProperty(BackstageAdapter.BACKSTAGE_REF_PROPERTY_NAME, entity.toBackstageRef());
                            container.addTags(new String[0]);
                        }
                        if (entity.metadata.tags != null) {
                            Set<String> existingTags = container.getTagsAsSet();
                            // New tags?
                            if (existingTags.addAll(entity.metadata.tags)) {
                                container.addTags(existingTags.toArray(new String[0]));
                                catalogWorkspace.setLastModifiedDate(new Date());
                            }
                        }
                    }
                }
            }
        }

        for (Workspace workspace : structurizrAdapter.GetCatalogWorkspaces()) {
            //gets set if a new relationship is created
            Relationship relationship = null;

            // find relationships from containers
            for (Entity entity : entities) {
                if (BackstageAdapter.BACKSTAGE_ENTITY_KIND_COMPONENT.equals(entity.kind)) {
                    for (Relation relation : entity.relations) {
                        if (BackstageAdapter.BACKSTAGE_RELATION_TYPE_DEPENDS_ON.equals(relation.type) || BackstageAdapter.BACKSTAGE_RELATION_TYPE_CONSUMES_API.equals(relation.type)) {
                            String sourceRef = entity.toBackstageRef();
                            String targetRef = relation.targetRef;
                            Container source = (Container) workspace.getModel().getElements().stream().filter(e -> e instanceof Container && sourceRef.equals(e.getProperties().get(BackstageAdapter.BACKSTAGE_REF_PROPERTY_NAME))).findFirst().orElse(null);
                            Element destination = workspace.getModel().getElements().stream().filter(e -> targetRef.equals(e.getProperties().get(BackstageAdapter.BACKSTAGE_REF_PROPERTY_NAME))).findFirst().orElse(null);

                            if (source != null && destination != null) {
                                if (destination instanceof SoftwareSystem) {
                                    relationship = source.uses((SoftwareSystem) destination, relation.type);
                                } else {
                                    relationship = source.uses((Container) destination, relation.type);
                                }
                            }
                        }
                    }
                }
            }

            // find relationships from software systems
            for (Entity entity : entities) {
                if (BackstageAdapter.BACKSTAGE_ENTITY_KIND_SYSTEM.equals(entity.kind)) {
                    for (Relation relation : entity.relations) {
                        if (BackstageAdapter.BACKSTAGE_RELATION_TYPE_DEPENDS_ON.equals(relation.type) || BackstageAdapter.BACKSTAGE_RELATION_TYPE_CONSUMES_API.equals(relation.type)) {
                            String sourceRef = entity.toBackstageRef();
                            String targetRef = relation.targetRef;
                            System.out.println(sourceRef + " -> " + targetRef);
                            SoftwareSystem source = (SoftwareSystem) workspace.getModel().getElements().stream().filter(e -> e instanceof SoftwareSystem &&
                                    sourceRef.equals(e.getProperties().get(BackstageAdapter.BACKSTAGE_REF_PROPERTY_NAME))).findFirst().orElse(null);
                            Element destination = workspace.getModel().getElements().stream().filter(e -> targetRef.equals(e.getProperties().get(BackstageAdapter.BACKSTAGE_REF_PROPERTY_NAME))).findFirst().orElse(null);

                            if (source != null && destination != null) {
                                if (destination instanceof SoftwareSystem) {
                                    relationship = source.uses((SoftwareSystem) destination, relation.type);
                                } else {
                                    relationship = source.uses((Container) destination, relation.type);
                                }
                            }
                        }
                    }
                }
            }
            if (relationship != null) {
                workspace.setLastModifiedDate(new Date());
            }
        }

        for (Workspace workspace : structurizrAdapter.GetCatalogWorkspaces()) {
            if (workspace.getConfiguration().getScope() != WorkspaceScope.SoftwareSystem) {
                continue;
            }

            SoftwareSystem softwareSystem = workspace.getModel().getSoftwareSystemWithName(workspace.getName());

            if (softwareSystem == null) {
                System.out.println("Can't add configure views for workspace " + workspace.getName() + " to without a software system.");
                continue;
            }

            String[] themes = workspace.getViews().getConfiguration().getThemes();
            if (!Arrays.asList(themes).contains("idesignTheme")) {
                workspace.getViews().getConfiguration().addTheme(StructurizrAdapter.IDESIGN_THEME_URL);
            }

            workspace.trim();
            System.out.println("Created " + workspace.getName() + " workspace from Backstage");


            structurizrAdapter.AddWorkspaceToLandscape(catalogSystemLandscapeWorkspace, workspace);
        }
    }
}

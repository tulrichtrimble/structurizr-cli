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
import org.apache.commons.cli.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.lang.reflect.Array;
import java.net.URI;
import java.util.Arrays;
import java.util.Date;
import java.util.List;
import java.util.Set;

public class SyncCatalogCommand extends AbstractCommand {

    private static final Log log = LogFactory.getLog(SyncCatalogCommand.class);

    @Override
    public void run(String... args) throws Exception , StructurizrClientException {
        Options options = new Options();

        Option option = new Option("url", "structurizrApiUrl", true, "The URL of the On Premesis instance to use for workspace identifiers and publishing");
        option.setRequired(true);
        options.addOption(option);

        option = new Option("key", "apiKey", true, "Workspace API key");
        option.setRequired(true);
        options.addOption(option);

        option = new Option("catalog-url", "catalog-url", true, "Location of the catalog entity API or file");
        option.setRequired(false);
        options.addOption(option);

        option = new Option("dslTemplate", "dslTemplate", true, "Templatized DSL file for use in extending workspaces");
        option.setRequired(false);
        options.addOption(option);

        option = new Option("workspaces", "workspaces", true, "Folder to store named workspaces");
        option.setRequired(false);
        options.addOption(option);

        CommandLineParser commandLineParser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();

        ApiConnection apiConnection = null;

        String url = "";
        String key = "";
        String catalog = "";
        String dslTemplatePath = "";
        String workspaceRoot = "";

        try{
            CommandLine cmd = commandLineParser.parse(options, args);
            url = cmd.getOptionValue("structurizrApiUrl", "https://arch-repo-fahxgzhxbqgbdmgt.centralus-01.azurewebsites.net");
            key = cmd.getOptionValue("apiKey", "TYLER_API_KEY");
            workspaceRoot = cmd.getOptionValue(
                    "workspaces",
                    System.getProperty("user.dir") + "\\..\\named-workspaces");
            catalog = cmd.getOptionValue(
                    "catalog-url",
                    System.getProperty("user.dir") + "\\src\\resources\\backstage-trimble-entities.json");
            dslTemplatePath = cmd.getOptionValue(
                    "dslTemplate",
                    System.getProperty("user.dir") + "\\src\\resources\\");

        }
        catch (ParseException e) {
            log.error(e.getMessage());
            formatter.printHelp("pull", options);

            System.exit(1);
        }

        apiConnection = new ApiConnection(url, key);

        StructurizrAdapter structurizrAdapter = new StructurizrAdapter(apiConnection);
        BackstageAdapter backstage = new BackstageAdapter();

        log.info("Pulling workspaces from " + url);
        structurizrAdapter.PullWorkspaces();

        // load entities from demo.backstage.io
        Entity[] entities = backstage.getEntitiesFromBackstage(catalog);
        BuildCatalogWorkspaces(entities, structurizrAdapter);

        URI structurizrHost = new URI(apiConnection.url);
        structurizrAdapter.SaveWorkspacesLocal(workspaceRoot, structurizrHost, dslTemplatePath);
    }

    private static void BuildCatalogWorkspaces(Entity[] entities, StructurizrAdapter structurizrAdapter) throws Exception, StructurizrClientException {
        // Create Systems
        for (Entity entity : entities) {
            if (BackstageAdapter.BACKSTAGE_ENTITY_KIND_SYSTEM.equals(entity.kind)) {
                String name = entity.metadata.name;
                Workspace catalogWorkspace = structurizrAdapter.GetCatalogWorkspace(name);
                if (catalogWorkspace == null) {
                    catalogWorkspace = structurizrAdapter.createShellWorkspace(name, entity.metadata.description, WorkspaceScope.SoftwareSystem);
                    // Prepare the entity to get a relationship set in Backstage
                    SoftwareSystem softwareSystem = catalogWorkspace.getModel().getSoftwareSystemWithName(name);
                    softwareSystem.addProperty(BackstageAdapter.BACKSTAGE_REF_PROPERTY_NAME, entity.toBackstageRef());
                    softwareSystem.addProperty(StructurizrAdapter.STRUCTURIZR_DSL_IDENTIFIER_PROPERTY_NAME, entity.metadata.name.replaceAll("\\W", ""));
                    catalogWorkspace = structurizrAdapter.CloneToCatalogWorkspace(catalogWorkspace);
                }

                SoftwareSystem softwareSystem = catalogWorkspace.getModel().getSoftwareSystemWithName(name);
                if (entity.metadata.tags != null){
                    Set<String> existingTags = softwareSystem.getTagsAsSet();
                    existingTags.addAll(entity.metadata.tags);
                    softwareSystem.addTags(existingTags.toArray(new String[0]));
                }
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
                    else{
                        System.out.println("Container [" + entity.metadata.name + "] does not have a SoftwareSystem set in the catalog and will be ignored. Set this value in the catalog to include it in the model.");
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


        List<Workspace> systems = structurizrAdapter.GetCatalogWorkspaces().stream().filter(w -> w.getConfiguration().getScope() == WorkspaceScope.SoftwareSystem).toList();
        for (Workspace workspace : systems) {
            SoftwareSystem softwareSystem = workspace.getModel().getSoftwareSystemWithName(workspace.getName());

            if (softwareSystem == null) {
                System.out.println("Can't add configure views for workspace " + workspace.getName() + " to without a software system.");
                continue;
            }

            String[] themes = workspace.getViews().getConfiguration().getThemes();
            if (!Arrays.asList(themes).contains("idesignTheme")) {
                workspace.getViews().getConfiguration().addTheme(StructurizrAdapter.IDESIGN_THEME_URL);
            }

            System.out.println("Created " + workspace.getName() + " workspace from catalog");

            structurizrAdapter.AddWorkspaceToCatalogLandscape(workspace);
        }
    }
}

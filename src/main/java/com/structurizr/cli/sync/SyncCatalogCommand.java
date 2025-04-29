package com.structurizr.cli.sync;

import com.structurizr.Workspace;
import com.structurizr.api.StructurizrClientException;
import com.structurizr.api.WorkspaceMetadata;
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
import org.yaml.snakeyaml.Yaml;
import org.apache.commons.cli.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.File;
import java.io.IOException;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.net.URI;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.stream.Collectors;

public class SyncCatalogCommand extends AbstractCommand {

    private static final Log log = LogFactory.getLog(SyncCatalogCommand.class);
    private static final String WORKSPACE_ID_ANNOTATION = "architecture-repository/workspace-id";
    
    // Input source types
    private enum InputType {
        YAML,
        JSON,
        API
    }

    @Override
    public void run(String... args) throws Exception, StructurizrClientException {
        Options options = new Options();

        Option option = new Option("url", "structurizrApiUrl", true, "The URL of the On Premises instance to use for workspace identifiers and publishing");
        option.setRequired(true);
        options.addOption(option);

        option = new Option("key", "apiKey", true, "Workspace API key");
        option.setRequired(true);
        options.addOption(option);

        option = new Option("catalog", "catalog", true, "Location of the catalog entity API, JSON file, or YAML file");
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
        String catalogLocation = "";
        String workspaceRoot = "";

        try {
            CommandLine cmd = commandLineParser.parse(options, args);
            url = cmd.getOptionValue("structurizrApiUrl", "https://arch-repo-fahxgzhxbqgbdmgt.centralus-01.azurewebsites.net");
            key = cmd.getOptionValue("apiKey", "TYLER_API_KEY");
            workspaceRoot = cmd.getOptionValue("workspaces"); // May be null
            catalogLocation = cmd.getOptionValue(
                    "catalog",
                    System.getProperty("user.dir") + "\\src\\resources\\backstage-trimble-entities.json");

        } catch (ParseException e) {
            log.error(e.getMessage());
            formatter.printHelp("sync-catalog", options);
            System.exit(1);
        }

        // Detect input type
        InputType inputType = determineInputType(catalogLocation);
        log.info("Detected input type: " + inputType);

        // Determine workspace directory
        Path archWorkspacesDir = determineWorkspaceDirectory(catalogLocation, workspaceRoot, inputType);

        apiConnection = new ApiConnection(url, key);

        // Initialize adapter and pull workspaces for ID matching
        StructurizrAdapter structurizrAdapter = new StructurizrAdapter(apiConnection);
        BackstageAdapter backstage = new BackstageAdapter();

        // Pull workspaces to get IDs (adapter will clear internal state first)
        log.info("Pulling workspaces from " + url + " (for ID matching)");
        structurizrAdapter.PullWorkspaces();

        Entity[] entities = loadEntities(catalogLocation, backstage);

        File catalogFile = (inputType == InputType.YAML) ? new File(catalogLocation) : null;

        List<Entity> systems = Arrays.stream(entities)
                .filter(e -> BackstageAdapter.BACKSTAGE_ENTITY_KIND_SYSTEM.equals(e.kind))
                .toList();

        for (Entity systemEntity : systems) {
            Integer workspaceId = createCatalogSystem(systemEntity, structurizrAdapter);

            if (inputType == InputType.YAML) {
                updateWorkspaceIdInCatalog(systemEntity, String.valueOf(workspaceId), catalogFile);
            }
        }

        processComponentEntities(entities, structurizrAdapter);
        buildRelationships(entities, structurizrAdapter);

        configureViewsAndLandscape(structurizrAdapter);
        
        // Save workspaces locally
        for (String systemName : structurizrAdapter.getCatalogSystemNames()) {
            Path systemDir = archWorkspacesDir.resolve(systemName);
            Files.createDirectories(systemDir);
            structurizrAdapter.SaveWorkspaceLocal(systemName, systemDir.toString());
            log.info("Saved catalog workspace for system " + systemName + " to " + systemDir);
        }
    }

    /**
     * Determines the type of input based on file extension or URL
     */
    private InputType determineInputType(String catalogLocation) {
        if (catalogLocation.toLowerCase().endsWith(".yaml") || 
            catalogLocation.toLowerCase().endsWith(".yml")) {
            return InputType.YAML;
        } else if (catalogLocation.toLowerCase().endsWith(".json")) {
            return InputType.JSON;
        } else if (catalogLocation.startsWith("http://") || 
                   catalogLocation.startsWith("https://")) {
            return InputType.API;
        }
        
        // Default to JSON for backward compatibility
        return InputType.JSON;
    }

    /**
     * Determines the workspace directory based on input type and provided workspace path
     */
    private Path determineWorkspaceDirectory(String catalogLocation, String workspaceRoot, InputType inputType) throws Exception {
        if (workspaceRoot != null) {
            // Use explicitly provided workspace directory
            Path workspacePath = Paths.get(workspaceRoot);
            Files.createDirectories(workspacePath);
            return workspacePath;
        } else if (inputType == InputType.YAML) {
            // For YAML input, create a docs/arch-workspaces directory where the catalog file resides
            File catalogFile = new File(catalogLocation);
            Path catalogDir = catalogFile.getParentFile().toPath();
            Path docsDir = catalogDir.resolve("docs");
            Path archWorkspacesDir = docsDir.resolve("arch-workspaces");
            Files.createDirectories(archWorkspacesDir);
            log.info("Created directory structure: " + archWorkspacesDir);
            return archWorkspacesDir;
        } else {
            // For JSON or API, workspace directory is required
            throw new IllegalArgumentException("The workspaces parameter is required when using JSON or API catalog sources.");
        }
    }

    /**
     * Loads entities from the provided source (YAML, JSON, or URL)
     */
    private Entity[] loadEntities(String catalogLocation, BackstageAdapter backstage) throws Exception {
        Entity[] entities = backstage.getEntitiesFromBackstage(catalogLocation);
        if (entities == null || entities.length == 0) {
            throw new IllegalArgumentException("No entities found in catalog source: " + catalogLocation);
        }
        log.info("Loaded " + entities.length + " entities from " + catalogLocation);
        return entities;
    }
    
    /**
     * Processes a system entity to create a new workspace with appropriate ID
     */
    private Integer createCatalogSystem(Entity systemEntity, StructurizrAdapter structurizrAdapter) throws Exception {
        String systemName = systemEntity.metadata.name;
        log.info("Processing system: " + systemName);

        // Check if system already has a workspace ID annotation
        String workspaceIdFromAnnotation = null;
        if (systemEntity.metadata.annotations != null && 
            systemEntity.metadata.annotations.containsKey(WORKSPACE_ID_ANNOTATION)) {
            workspaceIdFromAnnotation = systemEntity.metadata.annotations.get(WORKSPACE_ID_ANNOTATION);
            log.info("System has workspace ID in annotation: " + workspaceIdFromAnnotation);
        }

        Integer workspaceId = null;
        String idSource = "";

        if (workspaceIdFromAnnotation != null && !workspaceIdFromAnnotation.isEmpty()) {
            try {
                workspaceId = Integer.parseInt(workspaceIdFromAnnotation);
                idSource = "annotation";
            } catch (NumberFormatException e) {
                log.warn("Invalid workspace ID in annotation: " + workspaceIdFromAnnotation);
            }
        }
        if (workspaceId == null) {
            Workspace workspace = structurizrAdapter.GetWorkspace(systemName);
            if (workspace != null) {
                workspaceId = (int) workspace.getId();
            }
        }
        
        String description = systemEntity.metadata.description != null ? 
            systemEntity.metadata.description : systemName + " System";
        
        // Create a new catalog workspace
        Workspace shellWorkspace = structurizrAdapter.createShellWorkspace(systemName, description, WorkspaceScope.SoftwareSystem);
        Workspace catalogWorkspace = structurizrAdapter.RegisterCatalogWorkspace(shellWorkspace);

        SoftwareSystem softwareSystem = catalogWorkspace.getModel().getSoftwareSystemWithName(systemName);
        if (softwareSystem != null) {
            if (systemEntity.metadata.tags != null && !systemEntity.metadata.tags.isEmpty()) {
                softwareSystem.addTags(systemEntity.metadata.tags.toArray(new String[0]));
            }

            softwareSystem.addProperty(BackstageAdapter.BACKSTAGE_REF_PROPERTY_NAME, 
                                     "system:" + systemEntity.metadata.namespace + "/" + systemName);

            softwareSystem.addProperty(StructurizrAdapter.STRUCTURIZR_DSL_IDENTIFIER_PROPERTY_NAME, 
                                      systemEntity.metadata.name.replaceAll("\\W", ""));
        }

        log.info("Created catalog workspace for system: " + systemName +
                (workspaceId != null ? " with ID " + workspaceId + " (from " + idSource + ")" : ""));

        return workspaceId;
    }
    
    /**
     * Updates the workspace ID annotation in the catalog-info.yaml file
     */
    private void updateWorkspaceIdInCatalog(Entity systemEntity, String workspaceId, File catalogFile) {
        try {
            // Ensure annotations map exists
            if (systemEntity.metadata.annotations == null) {
                systemEntity.metadata.annotations = new HashMap<>();
            }
            
            // Update the annotation
            systemEntity.metadata.annotations.put(WORKSPACE_ID_ANNOTATION, workspaceId);
            
            if (catalogFile != null && catalogFile.exists() && catalogFile.isFile()) {
                // Read the file and update the annotation using SnakeYAML
                updateYamlWithSnakeYaml(catalogFile, systemEntity);
                log.info("Updated workspace ID in catalog file: " + catalogFile.getAbsolutePath());
            }
        } catch (Exception e) {
            log.error("Failed to update workspace ID in catalog file", e);
        }
    }
    
    /**
     * Uses SnakeYAML to update the workspace ID annotation in the YAML file
     */
    private void updateYamlWithSnakeYaml(File catalogFile, Entity systemEntity) throws IOException {
        Yaml yaml = new Yaml();
        List<Map<String, Object>> documents = new ArrayList<>();
        boolean updated = false;
        
        // Read all YAML documents from file
        try (FileInputStream input = new FileInputStream(catalogFile)) {
            for (Object obj : yaml.loadAll(input)) {
                if (obj instanceof Map) {
                    @SuppressWarnings("unchecked")
                    Map<String, Object> document = (Map<String, Object>) obj;
                    documents.add(document);
                    
                    // Check if this is the system entity we want to update
                    if (isMatchingSystemEntity(document, systemEntity)) {
                        // Update or add annotations section
                        @SuppressWarnings("unchecked")
                        Map<String, Object> metadata = (Map<String, Object>) document.get("metadata");
                        
                        if (metadata == null) {
                            metadata = new HashMap<>();
                            document.put("metadata", metadata);
                        }
                        
                        @SuppressWarnings("unchecked")
                        Map<String, Object> annotations = (Map<String, Object>) metadata.get("annotations");
                        
                        if (annotations == null) {
                            annotations = new HashMap<>();
                            metadata.put("annotations", annotations);
                        }
                        
                        // Set the annotation
                        annotations.put(WORKSPACE_ID_ANNOTATION, systemEntity.metadata.annotations.get(WORKSPACE_ID_ANNOTATION));
                        updated = true;
                    }
                }
            }
        }
        
        if (!updated) {
            log.warn("Could not find matching system entity in YAML to update the workspace ID");
            return;
        }
        
        // Write the updated YAML back to file
        try (FileWriter writer = new FileWriter(catalogFile)) {
            for (int i = 0; i < documents.size(); i++) {
                yaml.dump(documents.get(i), writer);
                if (i < documents.size() - 1) {
                    writer.write("---\n");
                }
            }
        }
    }
    
    /**
     * Checks if a YAML document represents the system entity we want to update
     */
    private boolean isMatchingSystemEntity(Map<String, Object> document, Entity systemEntity) {
        String kind = (String) document.get("kind");
        if (kind == null || !kind.equals(systemEntity.kind)) {
            return false;
        }
        
        @SuppressWarnings("unchecked")
        Map<String, Object> metadata = (Map<String, Object>) document.get("metadata");
        if (metadata == null) {
            return false;
        }
        
        String name = (String) metadata.get("name");
        return name != null && name.equals(systemEntity.metadata.name);
    }

    /**
     * Process component entities and add them to their respective software systems
     */
    private void processComponentEntities(Entity[] entities, StructurizrAdapter structurizrAdapter) throws Exception {
        // Add Containers
        for (Entity entity : entities) {
            if (BackstageAdapter.BACKSTAGE_ENTITY_KIND_COMPONENT.equals(entity.kind) || 
                BackstageAdapter.BACKSTAGE_ENTITY_KIND_RESOURCE.equals(entity.kind)) {
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
                            
                            // Add tags if available
                            if (entity.metadata.tags != null && !entity.metadata.tags.isEmpty()) {
                                container.addTags(entity.metadata.tags.toArray(new String[0]));
                            }
                        }
                    }
                    else{
                        log.info("Container [" + entity.metadata.name + "] does not have a SoftwareSystem set in the catalog and will be ignored. Set this value in the catalog to include it in the model.");
                    }
                }
            }
        }
    }
    
    /**
     * Build relationships between elements based on the catalog entities
     */
    private void buildRelationships(Entity[] entities, StructurizrAdapter structurizrAdapter) {
        for (Workspace workspace : structurizrAdapter.GetCatalogWorkspaces()) {
            // Skip the landscape workspace for relationship processing
            if (workspace.getName().equals(StructurizrAdapter.LANDSCAPE_WORKSPACE_NAME)) {
                continue;
            }
            
            //gets set if a new relationship is created
            Relationship relationship = null;

            // find relationships from containers
            for (Entity entity : entities) {
                if (BackstageAdapter.BACKSTAGE_ENTITY_KIND_COMPONENT.equals(entity.kind)) {
                    for (Relation relation : entity.relations) {
                        if (BackstageAdapter.BACKSTAGE_RELATION_TYPE_DEPENDS_ON.equals(relation.type) || 
                            BackstageAdapter.BACKSTAGE_RELATION_TYPE_CONSUMES_API.equals(relation.type)) {
                            String sourceRef = entity.toBackstageRef();
                            String targetRef = relation.targetRef;
                            Container source = (Container) workspace.getModel().getElements().stream()
                                .filter(e -> e instanceof Container && sourceRef.equals(e.getProperties().get(BackstageAdapter.BACKSTAGE_REF_PROPERTY_NAME)))
                                .findFirst().orElse(null);
                            Element destination = workspace.getModel().getElements().stream()
                                .filter(e -> targetRef.equals(e.getProperties().get(BackstageAdapter.BACKSTAGE_REF_PROPERTY_NAME)))
                                .findFirst().orElse(null);

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
                        if (BackstageAdapter.BACKSTAGE_RELATION_TYPE_DEPENDS_ON.equals(relation.type) || 
                            BackstageAdapter.BACKSTAGE_RELATION_TYPE_CONSUMES_API.equals(relation.type)) {
                            String sourceRef = entity.toBackstageRef();
                            String targetRef = relation.targetRef;
                            log.debug(sourceRef + " -> " + targetRef);
                            SoftwareSystem source = (SoftwareSystem) workspace.getModel().getElements().stream()
                                .filter(e -> e instanceof SoftwareSystem && sourceRef.equals(e.getProperties().get(BackstageAdapter.BACKSTAGE_REF_PROPERTY_NAME)))
                                .findFirst().orElse(null);
                            Element destination = workspace.getModel().getElements().stream()
                                .filter(e -> targetRef.equals(e.getProperties().get(BackstageAdapter.BACKSTAGE_REF_PROPERTY_NAME)))
                                .findFirst().orElse(null);

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
    }
    
    /**
     * Configure views for software system workspaces and add them to the landscape
     */
    private void configureViewsAndLandscape(StructurizrAdapter structurizrAdapter) {
        // First configure views for each system workspace
        List<Workspace> systemWorkspaces = new ArrayList<>();
        
        // Create a stable copy of the collection to prevent concurrent modification
        for (Workspace workspace : structurizrAdapter.GetCatalogWorkspaces()) {
            // Skip the landscape workspace
            if (!workspace.getName().equals(StructurizrAdapter.LANDSCAPE_WORKSPACE_NAME) &&
                    workspace.getConfiguration().getScope() == WorkspaceScope.SoftwareSystem) {
                    SoftwareSystem softwareSystem = workspace.getModel().getSoftwareSystemWithName(workspace.getName());

                    if (softwareSystem == null) {
                        log.warn("Can't configure views for workspace " + workspace.getName() + " without a software system.");
                        continue;
                    }

                    systemWorkspaces.add(workspace);
                    String[] themes = workspace.getViews().getConfiguration().getThemes();
                    if (!Arrays.asList(themes).contains("idesignTheme")) {
                        workspace.getViews().getConfiguration().addTheme(StructurizrAdapter.IDESIGN_THEME_URL);
                    }
            }
        }

        for (Workspace workspace : systemWorkspaces) {
            try {
                structurizrAdapter.AddWorkspaceToCatalogLandscape(workspace);
                log.info("Added " + workspace.getName() + " to landscape");
            } catch (Exception e) {
                log.error("Failed to add workspace to landscape: " + workspace.getName(), e);
            }
        }
    }
}

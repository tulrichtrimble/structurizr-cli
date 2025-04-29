package com.structurizr.cli.sync;

import com.structurizr.Workspace;
import com.structurizr.api.StructurizrClientException;
import com.structurizr.cli.AbstractCommand;
import com.structurizr.cli.sync.backstage.BackstageAdapter;
import com.structurizr.cli.sync.backstage.Entity;
import com.structurizr.configuration.WorkspaceScope;
import com.structurizr.model.SoftwareSystem;
import com.structurizr.util.StringUtils;
import org.apache.commons.cli.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.stream.Collectors;

public class InitCatalogCommand extends AbstractCommand {

    private static final Log log = LogFactory.getLog(InitCatalogCommand.class);
    private static final String WORKSPACE_ID_ANNOTATION = "architecture-repository/workspace-id";

    @Override
    public void run(String... args) throws Exception, StructurizrClientException {
        Options options = new Options();

        Option option = new Option("url", "structurizrApiUrl", true, "The URL of the On Premises instance to use for workspace identifiers and publishing");
        option.setRequired(true);
        options.addOption(option);

        option = new Option("key", "apiKey", true, "Workspace API key");
        option.setRequired(true);
        options.addOption(option);

        option = new Option("catalog", "catalogFile", true, "Path to catalog-info.yaml file");
        option.setRequired(true);
        options.addOption(option);

        CommandLineParser commandLineParser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();

        try {
            CommandLine cmd = commandLineParser.parse(options, args);
            String url = cmd.getOptionValue("structurizrApiUrl");
            String key = cmd.getOptionValue("apiKey");
            String catalogFilePath = cmd.getOptionValue("catalogFile");

            File catalogFile = new File(catalogFilePath);
            if (!catalogFile.exists() || !catalogFile.isFile()) {
                log.error("Catalog file not found: " + catalogFilePath);
                return;
            }

            // Create docs/arch-workspaces folder structure
            Path archWorkspacesDir = initWorkspaces(catalogFile);

            // Connect to OnPrem and pull workspaces
            ApiConnection apiConnection = new ApiConnection(url, key);
            StructurizrAdapter structurizrAdapter = new StructurizrAdapter(apiConnection);
            structurizrAdapter.PullWorkspaces();
            log.info("Successfully pulled workspaces from " + url);

            // Parse catalog file
            BackstageAdapter backstageAdapter = new BackstageAdapter();
            Entity[] entities = backstageAdapter.getEntitiesFromBackstage(catalogFilePath);
            
            if (entities == null || entities.length == 0) {
                log.error("No entities found in catalog file");
                return;
            }

            // Process systems in catalog
            List<Entity> systems = Arrays.stream(entities)
                    .filter(e -> BackstageAdapter.BACKSTAGE_ENTITY_KIND_SYSTEM.equals(e.kind))
                    .collect(Collectors.toList());

            log.info("Found " + systems.size() + " system(s) in catalog file");

            for (Entity systemEntity : systems) {
                processSystem(systemEntity, structurizrAdapter, archWorkspacesDir, catalogFile);
            }

            log.info("Initialization complete. Workspaces are located in: " + archWorkspacesDir);

        } catch (ParseException e) {
            log.error(e.getMessage());
            formatter.printHelp("init", options);
            System.exit(1);
        }
    }

    /**
     * Creates the docs/arch-workspaces directory structure
     * @param catalogFile The catalog file
     * @return Path to the arch-workspaces directory
     */
    private Path initWorkspaces(File catalogFile) throws Exception {
        Path catalogDir = catalogFile.getParentFile().toPath();
        Path docsDir = catalogDir.resolve("docs");
        Path archWorkspacesDir = docsDir.resolve("arch-workspaces");

        Files.createDirectories(archWorkspacesDir);
        log.info("Created directory structure: " + archWorkspacesDir);
        
        return archWorkspacesDir;
    }

    /**
     * Processes a system entity to either find an existing workspace or create a new one
     */
    private void processSystem(Entity systemEntity, StructurizrAdapter structurizrAdapter, 
                              Path archWorkspacesDir, File catalogFile) throws Exception {
        String systemName = systemEntity.metadata.name;
        log.info("Processing system: " + systemName);

        // Check if system already has a workspace ID annotation
        String workspaceId = null;
        if (systemEntity.metadata.annotations != null && 
            systemEntity.metadata.annotations.containsKey(WORKSPACE_ID_ANNOTATION)) {
            workspaceId = systemEntity.metadata.annotations.get(WORKSPACE_ID_ANNOTATION);
            log.info("System has workspace ID: " + workspaceId);
        }

        Workspace workspace = null;

        // Search for existing workspace by name first (prioritizing name match)
        workspace = structurizrAdapter.GetWorkspace(systemName);
        if (workspace != null) {
            log.info("Found workspace by name: " + workspace.getName());
            
            // If we found a workspace by name but the ID differs from what's in the catalog,
            // we need to update the catalog-info.yaml with the correct ID
            String newWorkspaceId = String.valueOf(workspace.getId());
            if (!newWorkspaceId.equals(workspaceId)) {
                log.info("Updating workspace ID in catalog file from " + 
                         (workspaceId != null ? workspaceId : "none") + " to " + newWorkspaceId);
                updateWorkspaceIdInCatalog(systemEntity, newWorkspaceId, catalogFile);
            }
        }
        
        // If not found by name, try to find by ID if we have one
        if (workspace == null && workspaceId != null && !workspaceId.isEmpty()) {
            workspace = structurizrAdapter.GetWorkspaceById(workspaceId);
            if (workspace != null) {
                log.info("Found existing workspace by ID: " + workspace.getName());
                
                // If the workspace exists but has a different name, we'll keep using it
                // but log a warning about the name mismatch
                if (!workspace.getName().equalsIgnoreCase(systemName)) {
                    log.info("Note: Workspace name '" + workspace.getName() + 
                            "' differs from system name '" + systemName + "'");
                }
            }
        }

        // If still no workspace, create a new one
        if (workspace == null) {
            String description = systemEntity.metadata.description != null ? 
                systemEntity.metadata.description : systemName + " System";
            
            workspace = structurizrAdapter.createShellWorkspace(systemName, description, WorkspaceScope.SoftwareSystem);
            
            // Add any tags from catalog entity to the software system
            if (systemEntity.metadata.tags != null && !systemEntity.metadata.tags.isEmpty()) {
                SoftwareSystem softwareSystem = workspace.getModel().getSoftwareSystemWithName(systemName);
                if (softwareSystem != null) {
                    softwareSystem.addTags(systemEntity.metadata.tags.toArray(new String[0]));
                    
                    // Add backstage reference property
                    softwareSystem.addProperty(BackstageAdapter.BACKSTAGE_REF_PROPERTY_NAME, 
                                             "system:" + systemEntity.metadata.namespace + "/" + systemName);
                }
            }
            
            log.info("Created new workspace for system: " + systemName);
            
            // Update the catalog-info.yaml with the new workspace ID
            updateWorkspaceIdInCatalog(systemEntity, String.valueOf(workspace.getId()), catalogFile);
        }

        // Clone the workspace (if needed) and save locally
        workspace = structurizrAdapter.CloneToCatalogWorkspace(workspace);
        
        // Create system-specific directory and save workspace files
        Path systemDir = archWorkspacesDir.resolve(systemName);
        Files.createDirectories(systemDir);
        
        // Save catalog-workspace.json
        Workspace catalogWorkspace = structurizrAdapter.GetCatalogWorkspace(systemName);
        if (catalogWorkspace != null) {
            structurizrAdapter.SaveWorkspaceLocal(systemName, systemDir.toString());
            log.info("Saved workspace files to: " + systemDir);
        } else {
            log.error("Failed to save workspace for system: " + systemName);
        }
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
            
            if (catalogFile.exists() && catalogFile.isFile()) {
                // Read the current content
                String content = Files.readString(catalogFile.toPath());
                
                // Look for the system entity and update its annotations
                updateYamlAnnotation(catalogFile, content, systemEntity);
                
                log.info("Updated workspace ID in catalog file: " + catalogFile.getAbsolutePath());
            }
        } catch (Exception e) {
            log.error("Failed to update workspace ID in catalog file", e);
        }
    }
    
    /**
     * Updates the YAML file with the new annotation value
     */
    private void updateYamlAnnotation(File catalogFile, String content, Entity systemEntity) throws Exception {
        // Simple approach: we're adding a new annotations section if it doesn't exist
        // or updating the existing one if it does
        
        // System entity definition typically starts with kind: System
        String systemPattern = "kind:\\s*" + systemEntity.kind + "[\\s\\S]*?metadata:[\\s\\S]*?name:\\s*" + 
                               systemEntity.metadata.name.replace("-", "\\-");
        
        // Check if there's already an annotations section
        if (content.matches("(?s).*" + systemPattern + "[\\s\\S]*?annotations:.*")) {
            // Annotations section exists, check if our annotation exists
            if (content.matches("(?s).*" + systemPattern + "[\\s\\S]*?annotations:[\\s\\S]*?" + 
                                WORKSPACE_ID_ANNOTATION.replace("/", "\\/") + ":\\s*.*")) {
                // Annotation exists, update it
                content = content.replaceAll(
                    "(?s)(" + systemPattern + "[\\s\\S]*?annotations:[\\s\\S]*?" + 
                    WORKSPACE_ID_ANNOTATION.replace("/", "\\/") + ":\\s*)\"?[^\"\\s,\\n]*\"?", 
                    "$1\"" + systemEntity.metadata.annotations.get(WORKSPACE_ID_ANNOTATION) + "\"");
            } else {
                // Annotation doesn't exist, add it to annotations section
                content = content.replaceAll(
                    "(?s)(" + systemPattern + "[\\s\\S]*?annotations:)([\\s\\S]*?)(\\n\\s+\\w+:|\\n---)", 
                    "$1$2\n    " + WORKSPACE_ID_ANNOTATION + ": \"" + 
                    systemEntity.metadata.annotations.get(WORKSPACE_ID_ANNOTATION) + "\"$3");
            }
        } else {
            // No annotations section, add it
            content = content.replaceAll(
                "(?s)(" + systemPattern + "[\\s\\S]*?)(\\n\\s+\\w+:|\\n---)", 
                "$1\n  annotations:\n    " + WORKSPACE_ID_ANNOTATION + ": \"" + 
                systemEntity.metadata.annotations.get(WORKSPACE_ID_ANNOTATION) + "\"$2");
        }
        
        // Write back to file
        Files.writeString(catalogFile.toPath(), content);
    }
    
    private String getTemplateResourcePath() {
        // Get path to the template resources - this may need to be adjusted based on your project structure
        try {
            URL url = getClass().getResource("/"); 
            if (url != null) {
                Path resourcesPath = Paths.get(url.toURI());
                // Navigate to the resources directory containing templates
                return resourcesPath.toString() + File.separator;
            }
        } catch (Exception e) {
            log.error("Error locating template resources", e);
        }
        
        // Fallback to a relative path
        return System.getProperty("user.dir") + File.separator + "src" + 
               File.separator + "resources" + File.separator;
    }
}
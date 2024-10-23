package com.structurizr.cli.sync;

import com.structurizr.Workspace;
import com.structurizr.api.AdminApiClient;
import com.structurizr.api.StructurizrClientException;
import com.structurizr.api.WorkspaceApiClient;
import com.structurizr.api.WorkspaceMetadata;
import com.structurizr.configuration.WorkspaceScope;
import com.structurizr.model.*;
import com.structurizr.util.WorkspaceUtils;
import com.structurizr.view.SystemLandscapeView;

import java.io.File;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.HashMap;
import java.util.List;
import java.util.Collection;
import java.util.Map;

public class StructurizrAdapter {
    public static final String LANDSCAPE_WORKSPACE_NAME = "Landscape";

    private static final String STRUCTURIZR_ONPREMISES_DOCKER_IMAGE = "structurizr/onpremises:latest";
    public static final String STRUCTURIZR_GROUP_SEPARATOR_PROPERTY_NAME = "structurizr.groupSeparator";
    public static final String STRUCTURIZR_DSL_IDENTIFIER_PROPERTY_NAME = "structurizr.dsl.identifier";
    public static final String OWNER_PERSPECTIVE_NAME = "Owner";
    public static final String IDESIGN_THEME_URL = "https://raw.githubusercontent.com/tulrichtrimble/backstage-repository/main/idesign_theme.json";


    Map<String,Workspace> _catalogWorkspacesByName = new HashMap<>();
    Map<String,Workspace> _workspacesByName = new HashMap<>();
    Map<String, WorkspaceMetadata> _workspaceMetadataByName = new HashMap<>();
    private ApiConnection _apiConnection;

    public StructurizrAdapter(ApiConnection apiConnection) {
        _apiConnection = apiConnection;
    }

    public void PullWorkspaces() throws StructurizrClientException {
        List<WorkspaceMetadata> workspaceMetadata = createAdminApiClient().getWorkspaces();
        for (WorkspaceMetadata metadata : workspaceMetadata) {
            if (!_workspaceMetadataByName.containsKey(metadata.getName())) {
                _workspaceMetadataByName.put(metadata.getName(), metadata);
            }

            Workspace workspace = createWorkspaceApiClient(metadata).getWorkspace(metadata.getId());
            if (!_workspacesByName.containsKey(metadata.getName())) {
                _workspacesByName.put(metadata.getName(), workspace);
            }
        }
    }

    protected void EnsureLandscape() throws StructurizrClientException{
        WorkspaceMetadata systemLandscapeWorkspaceMetadata = _workspaceMetadataByName.get(LANDSCAPE_WORKSPACE_NAME);

        if (systemLandscapeWorkspaceMetadata == null) {
            systemLandscapeWorkspaceMetadata = createAdminApiClient().createWorkspace();
            _workspaceMetadataByName.put(LANDSCAPE_WORKSPACE_NAME, systemLandscapeWorkspaceMetadata);
        }

        Workspace systemLandscapeWorkspace = _workspacesByName.get(LANDSCAPE_WORKSPACE_NAME);
        if (systemLandscapeWorkspace == null) {
            systemLandscapeWorkspace = CreateShellWorkspace(LANDSCAPE_WORKSPACE_NAME, "The system landscape, imported from Trimble Backstage", WorkspaceScope.Landscape);
            _workspacesByName.put(LANDSCAPE_WORKSPACE_NAME, systemLandscapeWorkspace);
        }
    }

    public Workspace GetWorkspace(String name) {
        return _workspacesByName.get(name);
    }

    public Workspace GetCatalogWorkspace(String name) {
        return _catalogWorkspacesByName.get(name);
    }

    public Collection<Workspace> GetWorkspaces() {
        return _workspacesByName.values();
    }

    public Collection<Workspace> GetCatalogWorkspaces() {
        return _catalogWorkspacesByName.values();
    }

    public void RegisterCatalogWorkspace(Workspace workspace) throws StructurizrClientException, Exception {
        WorkspaceMetadata workspaceMetadata = _workspaceMetadataByName.get(workspace.getName());

        if (workspaceMetadata == null) {
            workspaceMetadata = createAdminApiClient().createWorkspace();
            _workspaceMetadataByName.put(workspace.getName(), workspaceMetadata);
            _workspacesByName.put(workspace.getName(), workspace);
        }

        // Clone to catalog
        Workspace catalogWorkspace = _catalogWorkspacesByName.get(workspace.getName());
        if (catalogWorkspace == null) {
            catalogWorkspace = WorkspaceUtils.fromJson(WorkspaceUtils.toJson(workspace, false));
            _catalogWorkspacesByName.put(catalogWorkspace.getName(), catalogWorkspace);
            catalogWorkspace.setId(workspaceMetadata.getId());
        }
    }

    //TODO: Alter this function to do a workspace PUT for every modified workspace
    // Verify whether LastModifiedDate changes with update to workspace-backstage.json and/or DSL changes
    public void SaveWorkspaces(String sourceProperty) throws Exception, StructurizrClientException {
        // TODO: Compile from DSL in folders

        for (WorkspaceMetadata workspaceMetadata: _workspaceMetadataByName.values()) {
            Workspace updatedWorkspace = _workspacesByName.get(workspaceMetadata.getName());
            WorkspaceApiClient workspaceApiClient = createWorkspaceApiClient(workspaceMetadata);
            workspaceApiClient.setWorkspaceArchiveLocation(null);
            Workspace hostedWorkspace = workspaceApiClient.getWorkspace(workspaceMetadata.getId());

            if (!updatedWorkspace.getLastModifiedDate().equals(hostedWorkspace.getLastModifiedDate())){
                workspaceApiClient.putWorkspace(workspaceMetadata.getId(), updatedWorkspace);
            }
        }
    }

    public void SaveWorkspacesLocal(String baseWorkspacesFilePath, URI hostedStruturizerApi, String dslTemplatePath) throws Exception, StructurizrClientException {
        for (Workspace workspace: _workspacesByName.values()) {
            String folderPath = baseWorkspacesFilePath + "/" + workspace.getName();
            Path path = Path.of(folderPath);
            Files.createDirectories(path);

            Workspace catalogWorkspace = _catalogWorkspacesByName.get(workspace.getName());
            if (catalogWorkspace != null) {
                File catalogWorkspaceJson = new File(path.toFile(), "catalog-workspace.json");
                WorkspaceUtils.saveWorkspaceToJson(catalogWorkspace, catalogWorkspaceJson);

                // Initialize the workspace DSL
                File workspaceDslFile = new File(path.toFile(), "workspace.dsl");
                if (!workspaceDslFile.exists()) {
                    String dslTemplate = new String(Files.readAllBytes(Paths.get(dslTemplatePath)));
                    String dslRendered = dslTemplate.replace("{% workspace_path %}", "catalog-workspace.json");
                    Files.writeString(
                            workspaceDslFile.toPath(),
                            dslRendered,
                            java.nio.file.StandardOpenOption.CREATE,
                            java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                }
            }

            WorkspaceMetadata workspaceMetadata = _workspaceMetadataByName.get(workspace.getName());
            File workspaceHostPut = new File(path.toFile(), "structurizr-put.ps1");

            Files.writeString(
                    workspaceHostPut.toPath(),
                    "structurizr push -id "+ workspace.getId() +" -url "+ hostedStruturizerApi +"/api -key "+workspaceMetadata.getApiKey()+" -secret "+ workspaceMetadata.getApiSecret() +" -workspace workspace.json",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
        }
    }

    public void AddWorkspaceToLandscape(Workspace systemLandscapeWorkspace, Workspace workspace) throws IllegalArgumentException{

        SoftwareSystem softwareSystem = workspace.getModel().getSoftwareSystemWithName(workspace.getName());
        if (softwareSystem == null) {
            System.out.println("Can't add workspace " + workspace.getName() + " to Landscape without a primary software system.");
            return;
        }

        SoftwareSystem softwareSystemInLandscape = systemLandscapeWorkspace.getModel().getSoftwareSystemWithName(softwareSystem.getName());
        if (softwareSystemInLandscape == null){
            softwareSystemInLandscape = systemLandscapeWorkspace.getModel().addSoftwareSystem(softwareSystem.getName());
            softwareSystemInLandscape.setDescription(softwareSystem.getDescription());
            Map<String, String> props = softwareSystem.getProperties();
            for (String key : props.keySet()) {
                softwareSystemInLandscape.addProperty(key, props.get(key));
            }
            for (Perspective perspective : softwareSystem.getPerspectives()) {
                softwareSystemInLandscape.addPerspective(perspective.getName(), perspective.getDescription());
            }
            softwareSystemInLandscape.setGroup(softwareSystem.getGroup());
            softwareSystemInLandscape.addTags(softwareSystem.getTags());
        }

        softwareSystemInLandscape.setUrl("{workspace:" + workspace.getId() + "}/diagrams#SystemContext");

        findAndCloneRelationships(workspace, systemLandscapeWorkspace);

        SystemLandscapeView landscapeView = systemLandscapeWorkspace.getViews().getSystemLandscapeViews().stream().filter(lsView -> lsView.getKey().equals(LANDSCAPE_WORKSPACE_NAME)).findFirst().orElse(null);
        if (landscapeView == null) {
            landscapeView = systemLandscapeWorkspace.getViews().createSystemLandscapeView(LANDSCAPE_WORKSPACE_NAME, "An automatically generated system landscape view.");
        }

        //TODO: Check idempotency of function
        landscapeView.addAllElements();
    }

    public AdminApiClient createAdminApiClient() {
        return new AdminApiClient(_apiConnection.url + "/api", null, _apiConnection.apiKeyPlainText);
    }

    public WorkspaceApiClient createWorkspaceApiClient(WorkspaceMetadata workspaceMetadata) {
        WorkspaceApiClient workspaceApiClient = new WorkspaceApiClient(_apiConnection.url + "/api", workspaceMetadata.getApiKey(), workspaceMetadata.getApiSecret());
        workspaceApiClient.setWorkspaceArchiveLocation(null); // this prevents the local file system from being cluttered with JSON files

        return workspaceApiClient;
    }

    public Workspace CreateShellWorkspace(String name, String description, WorkspaceScope scope){
        Workspace workspace = new Workspace(name, description);
        workspace.getConfiguration().setScope(scope);
        workspace.getViews().getConfiguration().addTheme(IDESIGN_THEME_URL);
        workspace.getModel().addProperty(STRUCTURIZR_GROUP_SEPARATOR_PROPERTY_NAME, "/");

        if (scope == WorkspaceScope.SoftwareSystem){
            SoftwareSystem softwareSystem = workspace.getModel().addSoftwareSystem(name, description);
            softwareSystem.setUrl("{workspace:" + workspace.getId() + "}/diagrams#SystemContext");
        }

        return workspace;
    }

    protected static void findAndCloneRelationships(Workspace source, Workspace destination) {
        for (Relationship relationship : source.getModel().getRelationships()) {
            if (isPersonOrSoftwareSystem(relationship.getSource()) && isPersonOrSoftwareSystem(relationship.getDestination())) {
                cloneRelationshipIfItDoesNotExist(relationship, destination.getModel());
            }
        }
    }

    private static boolean isPersonOrSoftwareSystem(Element element) {
        return element instanceof Person || element instanceof SoftwareSystem;
    }

    private static void cloneRelationshipIfItDoesNotExist(Relationship relationship, Model model) {
        Relationship clonedRelationship = null;

        if (relationship.getSource() instanceof SoftwareSystem && relationship.getDestination() instanceof SoftwareSystem) {
            SoftwareSystem source = model.getSoftwareSystemWithName(relationship.getSource().getName());
            SoftwareSystem destination = model.getSoftwareSystemWithName(relationship.getDestination().getName());

            if (source != null && destination != null && !source.hasEfferentRelationshipWith(destination)) {
                clonedRelationship = source.uses(destination, relationship.getDescription());
            }
        } else if (relationship.getSource() instanceof Person && relationship.getDestination() instanceof SoftwareSystem) {
            Person source = model.getPersonWithName(relationship.getSource().getName());
            SoftwareSystem destination = model.getSoftwareSystemWithName(relationship.getDestination().getName());

            if (source != null && destination != null && !source.hasEfferentRelationshipWith(destination)) {
                clonedRelationship = source.uses(destination, relationship.getDescription());
            }
        } else if (relationship.getSource() instanceof SoftwareSystem && relationship.getDestination() instanceof Person) {
            SoftwareSystem source = model.getSoftwareSystemWithName(relationship.getSource().getName());
            Person destination = model.getPersonWithName(relationship.getDestination().getName());

            if (source != null && destination != null && !source.hasEfferentRelationshipWith(destination)) {
                clonedRelationship = source.delivers(destination, relationship.getDescription());
            }
        } else if (relationship.getSource() instanceof Person && relationship.getDestination() instanceof Person) {
            Person source = model.getPersonWithName(relationship.getSource().getName());
            Person destination = model.getPersonWithName(relationship.getDestination().getName());

            if (source != null && destination != null && !source.hasEfferentRelationshipWith(destination)) {
                clonedRelationship = source.delivers(destination, relationship.getDescription());
            }
        }

        if (clonedRelationship != null) {
            clonedRelationship.addTags(relationship.getTags());
        }
    }
}

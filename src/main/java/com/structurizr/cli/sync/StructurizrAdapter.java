package com.structurizr.cli.sync;

import com.structurizr.Workspace;
import com.structurizr.api.AdminApiClient;
import com.structurizr.api.StructurizrClientException;
import com.structurizr.api.WorkspaceApiClient;
import com.structurizr.api.WorkspaceMetadata;
import com.structurizr.dsl.StructurizrDslParser;
import com.structurizr.configuration.WorkspaceScope;
import com.structurizr.model.*;
import com.structurizr.util.StringUtils;
import com.structurizr.util.WorkspaceUtils;
import com.structurizr.validation.WorkspaceScopeValidatorFactory;
import com.structurizr.view.SystemLandscapeView;

import java.io.File;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class StructurizrAdapter {
    public static final String LANDSCAPE_WORKSPACE_NAME = "Landscape";

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

    public Workspace CloneToCatalogWorkspace(Workspace workspace) throws StructurizrClientException, Exception {
        WorkspaceMetadata workspaceMetadata = _workspaceMetadataByName.get(workspace.getName());

        if (workspaceMetadata == null) {
            workspaceMetadata = createAdminApiClient().createWorkspace();
            System.out.println("Created workspace [" + workspaceMetadata.getId() + "] for [" + workspace.getName() +"]");
            _workspaceMetadataByName.put(workspace.getName(), workspaceMetadata);
            _workspacesByName.put(workspace.getName(), workspace);
        }

        // Clone to catalog
        Workspace catalogWorkspace = _catalogWorkspacesByName.get(workspace.getName());
        if (catalogWorkspace == null) {
            catalogWorkspace = WorkspaceUtils.fromJson(WorkspaceUtils.toJson(workspace, false));
            _catalogWorkspacesByName.put(catalogWorkspace.getName(), catalogWorkspace);
            catalogWorkspace.setId(workspaceMetadata.getId());

            if (StringUtils.isNullOrEmpty(workspaceMetadata.getName())){
                WorkspaceApiClient workspaceApiClient = createWorkspaceApiClient(workspaceMetadata);
                workspaceApiClient.setWorkspaceArchiveLocation(null);

                System.out.println("Updating name of workspace id [" + workspaceMetadata.getId() + "] to [" + workspace.getName() +"] OnPrem");
                workspaceApiClient.putWorkspace(workspaceMetadata.getId(), catalogWorkspace);
            }
        }

        return catalogWorkspace;
    }

    //TODO: Alter this function to do a workspace PUT for every modified workspace
    // Verify whether LastModifiedDate changes with update to workspace-backstage.json and/or DSL changes
    public void PushWorkspaces(String baseWorkspacesFilePath) throws Exception, StructurizrClientException {

        for (WorkspaceMetadata workspaceMetadata: _workspaceMetadataByName.values()) {
            Workspace hostedWorkspace = _workspacesByName.get(workspaceMetadata.getName());
            String folderPath = baseWorkspacesFilePath + "/" + hostedWorkspace.getName();
            Path path = Path.of(folderPath);
            File workspaceDslFile = new File(path.toFile(), "workspace.dsl");

            if (workspaceDslFile.exists()) {
                StructurizrDslParser parser = new StructurizrDslParser();
                parser.parse(workspaceDslFile);
                Workspace localWorkspace = parser.getWorkspace();
                WorkspaceScopeValidatorFactory.getValidator(localWorkspace).validate(localWorkspace);

                if (localWorkspace.getLastModifiedDate().after(hostedWorkspace.getLastModifiedDate())){
                    System.out.println("Pushing locally updated [" + workspaceMetadata.getName() + "] to OnPrem.");
                    WorkspaceApiClient workspaceApiClient = createWorkspaceApiClient(workspaceMetadata);
                    workspaceApiClient.setMergeFromRemote(false);
                    workspaceApiClient.putWorkspace(workspaceMetadata.getId(), localWorkspace);
                }
                else{
                    System.out.println("OnPrem [" + workspaceMetadata.getName() + "] was updated more recently than local.");
                }
            }
            else {
                System.out.println("The onPrem workspace [" + workspaceMetadata.getName() + "] did not have a corresponding folder locally. This may indicate some shenanigans with renaming that should be resolved.");
            }
        }
    }

    public void SaveWorkspacesLocal(String baseWorkspacesFilePath, URI hostedStruturizerApi, String dslTemplatePath) throws Exception, StructurizrClientException {
        for (Workspace workspace: _workspacesByName.values()) {
            String folderPath = baseWorkspacesFilePath + "/" + workspace.getName();
            Path path = Path.of(folderPath);
            Files.createDirectories(path);
            System.out.println("Updating local workspace:" + path);

            String landscapeDslTemplate = new String(Files.readAllBytes(Paths.get(dslTemplatePath + "TokenizedLandscapeWorkspace.dsl")));
            String systemDslTemplate = new String(Files.readAllBytes(Paths.get(dslTemplatePath + "TokenizedSystemWorkspace.dsl")));

            Workspace catalogWorkspace = _catalogWorkspacesByName.get(workspace.getName());
            if (catalogWorkspace != null) {
                File catalogWorkspaceJson = new File(path.toFile(), "catalog-workspace.json");
                WorkspaceUtils.saveWorkspaceToJson(catalogWorkspace, catalogWorkspaceJson);

                // Initialize the workspace DSL
                File workspaceDslFile = new File(path.toFile(), "workspace.dsl");
                if (!workspaceDslFile.exists()) {
                    System.out.println("New DSL file in" + path);
                    String dslRendered = "";
                    if (catalogWorkspace.getConfiguration().getScope() == WorkspaceScope.SoftwareSystem) {
                        String dslIdentifier = catalogWorkspace.getModel().getSoftwareSystemWithName(catalogWorkspace.getName())
                                .getProperties().get(StructurizrAdapter.STRUCTURIZR_DSL_IDENTIFIER_PROPERTY_NAME);
                        dslRendered = systemDslTemplate
                            .replace("{% workspace_path %}", "catalog-workspace.json")
                            .replace("{% system_dsl_name %}", dslIdentifier);
                    }
                    else {
                        dslRendered = landscapeDslTemplate
                            .replace("{% workspace_path %}", "catalog-workspace.json");
                    }

                    Files.writeString(
                        workspaceDslFile.toPath(),
                        dslRendered,
                        java.nio.file.StandardOpenOption.CREATE,
                        java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                }
            }

            WorkspaceMetadata workspaceMetadata = _workspaceMetadataByName.get(workspace.getName());
            File workspaceHostPut = new File(path.toFile(), "structurizr-put.ps1");

            /*
            --removed since replaced by Sync Catalog
            Files.writeString(
                    workspaceHostPut.toPath(),
                    "structurizr push -id "+ workspace.getId() +" -url "+ hostedStruturizerApi +"/api -key "+workspaceMetadata.getApiKey()+" -secret "+ workspaceMetadata.getApiSecret() +" -workspace workspace.json",
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);*/
        }
    }

    public void AddWorkspaceToCatalogLandscape(Workspace workspace) throws IllegalArgumentException, Exception {
        Workspace catalogSystemLandscapeWorkspace = _catalogWorkspacesByName.get(StructurizrAdapter.LANDSCAPE_WORKSPACE_NAME);
        // Whether the landscape was updated
        boolean isDirty = false;
        if (catalogSystemLandscapeWorkspace == null) {
            Workspace hostedLandscape = _workspacesByName.get(StructurizrAdapter.LANDSCAPE_WORKSPACE_NAME);
            if (hostedLandscape != null){
                catalogSystemLandscapeWorkspace = CloneToCatalogWorkspace(hostedLandscape);
            }
            else {
                catalogSystemLandscapeWorkspace = createShellWorkspace(StructurizrAdapter.LANDSCAPE_WORKSPACE_NAME, "The Trimble Architectural System Landscape", WorkspaceScope.Landscape);
            }
            isDirty = true;
        }

        SoftwareSystem softwareSystem = workspace.getModel().getSoftwareSystemWithName(workspace.getName());
        if (softwareSystem == null) {
            System.out.println("Can't add workspace " + workspace.getName() + " to Landscape without a primary software system.");
            return;
        }

        System.out.println("Adding [" + workspace.getName() + "] to landscape.");
        SoftwareSystem softwareSystemInLandscape = catalogSystemLandscapeWorkspace.getModel().getSoftwareSystemWithName(softwareSystem.getName());
        if (softwareSystemInLandscape == null){
            softwareSystemInLandscape = catalogSystemLandscapeWorkspace.getModel().addSoftwareSystem(softwareSystem.getName());
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
            isDirty = true;
        }

        softwareSystemInLandscape.setUrl("{workspace:" + workspace.getId() + "}/diagrams#SystemContext");

        boolean newRelations = findAndCloneRelationships(workspace, catalogSystemLandscapeWorkspace);
        if (newRelations) {
            isDirty = true;
        }

        SystemLandscapeView landscapeView = catalogSystemLandscapeWorkspace.getViews().getSystemLandscapeViews().stream().filter(lsView -> lsView.getKey().equals(LANDSCAPE_WORKSPACE_NAME)).findFirst().orElse(null);
        if (landscapeView == null) {
            landscapeView = catalogSystemLandscapeWorkspace.getViews().createSystemLandscapeView(LANDSCAPE_WORKSPACE_NAME, "An automatically generated system landscape view.");
            isDirty = true;
        }

        if (isDirty || catalogSystemLandscapeWorkspace.getLastModifiedDate() == null){
            catalogSystemLandscapeWorkspace.setLastModifiedDate(new Date());
        }

        //TODO: Should be fine to run every time, but it is unclear what it actually accomplishes
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

    public Workspace createShellWorkspace(String name, String description, WorkspaceScope scope){
        Workspace workspace = new Workspace(name, description);
        workspace.getConfiguration().setScope(scope);
        workspace.getViews().getConfiguration().addTheme(IDESIGN_THEME_URL);
        workspace.getModel().addProperty(STRUCTURIZR_GROUP_SEPARATOR_PROPERTY_NAME, "/");

        if (scope == WorkspaceScope.SoftwareSystem){
            SoftwareSystem softwareSystem = workspace.getModel().addSoftwareSystem(name, description);
            softwareSystem.setUrl("{workspace:" + workspace.getId() + "}/diagrams#SystemContext");
        }
        workspace.setLastModifiedDate(new Date());

        return workspace;
    }

    protected static boolean findAndCloneRelationships(Workspace source, Workspace destination) {
        // Whether a relationship was cloned
        boolean isDirty = false;
        for (Relationship relationship : source.getModel().getRelationships()) {
            if (isPersonOrSoftwareSystem(relationship.getSource()) && isPersonOrSoftwareSystem(relationship.getDestination())) {
                boolean newRelations = cloneRelationshipIfItDoesNotExist(relationship, destination.getModel());
                if (newRelations){
                    isDirty = true;
                }
            }
        }

        return isDirty;
    }

    private static boolean isPersonOrSoftwareSystem(Element element) {
        return element instanceof Person || element instanceof SoftwareSystem;
    }

    // returns: whether a relationship was cloned
    private static boolean cloneRelationshipIfItDoesNotExist(Relationship relationship, Model model) {
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
            return true;
        }
        return false;
    }
}

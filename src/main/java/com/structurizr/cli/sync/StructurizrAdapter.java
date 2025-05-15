package com.structurizr.cli.sync;

import com.structurizr.Workspace;
import com.structurizr.api.AdminApiClient;
import com.structurizr.api.StructurizrClientException;
import com.structurizr.api.WorkspaceApiClient;
import com.structurizr.api.WorkspaceMetadata;
import com.structurizr.cli.sync.backstage.BackstageAdapter;
import com.structurizr.dsl.StructurizrDslParser;
import com.structurizr.configuration.WorkspaceScope;
import com.structurizr.model.*;
import com.structurizr.util.StringUtils;
import com.structurizr.util.WorkspaceUtils;
import com.structurizr.validation.WorkspaceScopeValidatorFactory;
import com.structurizr.view.SystemLandscapeView;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
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

    /**
     * Pull workspaces from the API, clearing any existing workspace data first
     */
    public void PullWorkspaces() throws StructurizrClientException {
        // Clear existing data
        _workspacesByName.clear();
        _catalogWorkspacesByName.clear();
        _workspaceMetadataByName.clear();
        
        // Pull new data
        List<WorkspaceMetadata> workspaceMetadata = createAdminApiClient().getWorkspaces();
        for (WorkspaceMetadata metadata : workspaceMetadata) {
            _workspaceMetadataByName.put(metadata.getName(), metadata);
            WorkspaceApiClient apiClient = createWorkspaceApiClient(metadata);
            apiClient.setMergeFromRemote(true);
            Workspace workspace = apiClient.getWorkspace(metadata.getId());
            _workspacesByName.put(metadata.getName(), workspace);
        }
    }

    public Workspace GetWorkspace(String name) {
        return _workspacesByName.get(name);
    }

    public Workspace GetWorkspaceById(String id) {
        for (Workspace existingWorkspace : _workspacesByName.values()) {
            if (String.valueOf(existingWorkspace.getId()).equals(id)) {
                return existingWorkspace;
            }
        }
        return null;
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

    public Element GetCatalogElementByRef(String sourceRef){
        if (StringUtils.isNullOrEmpty(sourceRef)) {
            return null;
        }
        for (Workspace workspace : _catalogWorkspacesByName.values()) {
            StaticStructureElement element = (StaticStructureElement) workspace.getModel().getElements().stream()
                    .filter(e -> e instanceof StaticStructureElement &&
                            sourceRef.equals(e.getProperties().get(BackstageAdapter.BACKSTAGE_REF_PROPERTY_NAME)))
                    .findFirst().orElse(null);

            if (element != null) {
                return element;
            }
        }

        return null;
    }
    
    public Workspace RegisterCatalogWorkspace(Workspace nonCatalogWorkspace) throws StructurizrClientException, Exception {
        WorkspaceMetadata workspaceMetadata = _workspaceMetadataByName.get(nonCatalogWorkspace.getName());
        String name = nonCatalogWorkspace.getName();

        if (workspaceMetadata == null) {
            workspaceMetadata = createAdminApiClient().createWorkspace();
            System.out.println("Created workspace [" + workspaceMetadata.getId() + "] for [" + name +"]");
            _workspaceMetadataByName.put(name, workspaceMetadata);
            _workspacesByName.put(name, nonCatalogWorkspace);
        }
        nonCatalogWorkspace.setId(workspaceMetadata.getId());

        Workspace catalogWorkspace = _catalogWorkspacesByName.get(name);
        if (catalogWorkspace == null) {
            catalogWorkspace = WorkspaceUtils.fromJson(WorkspaceUtils.toJson(nonCatalogWorkspace, false));
            _catalogWorkspacesByName.put(catalogWorkspace.getName(), catalogWorkspace);
            catalogWorkspace.setId(workspaceMetadata.getId());
        }

        // If the names differ, it was a new workspace. Push it up so all is in sync.
        // Then repull metadata so we have the updated name.
        if (!catalogWorkspace.getName().equals(workspaceMetadata.getName())){
            WorkspaceApiClient workspaceApiClient = createWorkspaceApiClient(workspaceMetadata);
            System.out.println("Updating name of workspace id [" + workspaceMetadata.getId() + "] to [" + name +"] OnPrem");
            workspaceApiClient.putWorkspace(workspaceMetadata.getId(), catalogWorkspace);
            catalogWorkspace.setId(workspaceMetadata.getId());
            PullWorkspaces();
        }

        return catalogWorkspace;
    }

    public boolean ContentsAreEqual(Workspace workspace1, Workspace workspace2) throws Exception{
        Workspace tempWorkspace1 = WorkspaceUtils.fromJson(WorkspaceUtils.toJson(workspace1, false));
        Workspace tempWorkspace2 = WorkspaceUtils.fromJson(WorkspaceUtils.toJson(workspace2, false));

        tempWorkspace1.setLastModifiedDate(tempWorkspace2.getLastModifiedDate());
        tempWorkspace1.setLastModifiedAgent("");
        tempWorkspace1.setLastModifiedUser("");
        tempWorkspace2.setLastModifiedAgent("");
        tempWorkspace2.setLastModifiedUser("");

        // Remove all the relationship dsl identifiers.
        // They appear to dispense random guids that are not updated on push
        for (Relationship relationship: tempWorkspace1.getModel().getRelationships()){
            relationship.addProperty(StructurizrAdapter.STRUCTURIZR_DSL_IDENTIFIER_PROPERTY_NAME, "x");
        }
        for (Relationship relationship: tempWorkspace2.getModel().getRelationships()){
            relationship.addProperty(StructurizrAdapter.STRUCTURIZR_DSL_IDENTIFIER_PROPERTY_NAME, "x");
        }

        String workspace1AsString = WorkspaceUtils.toJson(tempWorkspace1, false);
        String workspace2AsString = WorkspaceUtils.toJson(tempWorkspace2, false);

        return workspace1AsString.equals(workspace2AsString);
    }

    // Verify whether LastModifiedDate changes with update to workspace-backstage.json and/or DSL changes
    public void PushWorkspaces(File baseWorkspacesFilePath) throws Exception, StructurizrClientException {
        for (WorkspaceMetadata workspaceMetadata: _workspaceMetadataByName.values()) {
            Workspace hostedWorkspace = _workspacesByName.get(workspaceMetadata.getName());
            String folderPath = baseWorkspacesFilePath + "/" + hostedWorkspace.getName();
            Path path = Path.of(folderPath);
            File workspaceDslFile = new File(path.toFile(), "workspace.dsl");

            if (workspaceDslFile.exists()) {
                StructurizrDslParser parser = new StructurizrDslParser();
                parser.parse(workspaceDslFile);
                Workspace localDslWorkspace = parser.getWorkspace();
                localDslWorkspace.setLastModifiedDate(new Date());
                WorkspaceScopeValidatorFactory.getValidator(localDslWorkspace).validate(localDslWorkspace);

                //Ensure a workspace.json file exists as parsed from DSL
                File localJsonWorkspaceFile = new File(path.toFile(), "workspace.json");
                Workspace localJsonWorkspace;
                if (localJsonWorkspaceFile.exists()){
                    localJsonWorkspace = WorkspaceUtils.loadWorkspaceFromJson(localJsonWorkspaceFile);
                }
                else{
                    WorkspaceUtils.saveWorkspaceToJson(localDslWorkspace, localJsonWorkspaceFile);
                    localJsonWorkspace = localDslWorkspace;
                }

                localDslWorkspace.getViews().copyLayoutInformationFrom(localJsonWorkspace.getViews());

                if (!ContentsAreEqual(localDslWorkspace, hostedWorkspace)){
                    System.out.println("Workspace [" + workspaceMetadata.getName() + "] differs from the hosted version. Pushing to OnPrem.");
                    WorkspaceApiClient workspaceApiClient = createWorkspaceApiClient(workspaceMetadata);
                    workspaceApiClient.setMergeFromRemote(false);
                    workspaceApiClient.putWorkspace(workspaceMetadata.getId(), localDslWorkspace);
                }
                else{
                    System.out.println("OnPrem and local [" + workspaceMetadata.getName() + "] do not differ and will not be pushed OnPrem.");
                }
            }
            else {
                System.out.println("The onPrem workspace [" + workspaceMetadata.getName() + "] did not have a corresponding folder locally. This may indicate some shenanigans with renaming that should be resolved.");
            }
        }
    }

    /**
     * Saves all workspaces locally with template-based DSL files to separate subdirectories
     * 
     * @param basePath Base path where workspace subdirectories will be created
     * @throws Exception If an error occurs during saving
     * @throws StructurizrClientException If a Structurizr API error occurs
     */
    public void saveWorkspacesLocal(Path basePath) throws Exception, StructurizrClientException {
        for (String systemName : _catalogWorkspacesByName.keySet()) {
            Path systemDir = basePath.resolve(systemName);
            Files.createDirectories(systemDir);
            saveWorkspaceLocal(systemName, systemDir.toString());
            System.out.println("Saved catalog workspace for system " + systemName + " to " + systemDir);
        }
    }

    /**
     * Saves a single workspace to a specific directory
     * 
     * @param workspaceName Name of the workspace to save
     * @param directoryPath Direct path to the directory where files should be saved (no subdirectories)
     * @throws StructurizrClientException If a Structurizr API error occurs
     */
    public void saveWorkspaceLocal(String workspaceName, String directoryPath) throws Exception, StructurizrClientException {
        // Load templates from classpath resources
        String landscapeDslTemplate = loadResourceAsString("/TokenizedLandscapeWorkspace.dsl");
        String systemDslTemplate = loadResourceAsString("/TokenizedSystemWorkspace.dsl");

        Workspace catalogWorkspace = _catalogWorkspacesByName.get(workspaceName);
        if (catalogWorkspace != null) {
            Path path = Path.of(directoryPath);
            File catalogWorkspaceJson = new File(path.toFile(), "catalog-workspace.json");
            WorkspaceUtils.saveWorkspaceToJson(catalogWorkspace, catalogWorkspaceJson);

            // Initialize the workspace DSL
            File workspaceDslFile = new File(path.toFile(), "workspace.dsl");
            if (!workspaceDslFile.exists()) {
                System.out.println("New DSL file in " + path);
                String dslRendered = "";
                if (catalogWorkspace.getConfiguration().getScope() == WorkspaceScope.SoftwareSystem) {
                    SoftwareSystem system = catalogWorkspace.getModel().getSoftwareSystemWithName(catalogWorkspace.getName());
                    String dslIdentifier = system.getProperties().get(StructurizrAdapter.STRUCTURIZR_DSL_IDENTIFIER_PROPERTY_NAME);
                    dslRendered = systemDslTemplate
                        .replace("{% workspace_path %}", "catalog-workspace.json")
                        .replace("{% system_dsl_name %}", dslIdentifier);
                    
                    StringBuilder containerDslNames = new StringBuilder();
                    for (Container container : system.getContainers()) {
                        containerDslNames
                            .append("            !element \"")
                            .append(container.getProperties().get(StructurizrAdapter.STRUCTURIZR_DSL_IDENTIFIER_PROPERTY_NAME))
                            .append("\" {").append("\n").append("            }").append("\n\n");
                    }
                    
                    dslRendered = dslRendered.replace("{% containers %}", containerDslNames);
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
    }


    /**
     * Load a resource file from the classpath as a string
     * @param resourcePath Path to the resource (should start with a slash)
     * @return Content of the resource as string
     * @throws IOException If the resource cannot be read
     */
    private String loadResourceAsString(String resourcePath) throws IOException {
        try (InputStream is = StructurizrAdapter.class.getResourceAsStream(resourcePath)) {
            if (is == null) {
                throw new IOException("Resource not found: " + resourcePath);
            }
            return new String(is.readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    public void AddWorkspaceToCatalogLandscape(Workspace workspace, Workspace landscape) throws IllegalArgumentException, Exception {
        boolean isDirty = false;

        SoftwareSystem softwareSystem = workspace.getModel().getSoftwareSystemWithName(workspace.getName());
        if (softwareSystem == null) {
            System.out.println("Can't add workspace " + workspace.getName() + " to Landscape without a primary software system.");
            return;
        }

        System.out.println("Adding [" + workspace.getName() + "] to landscape.");
        SoftwareSystem softwareSystemInLandscape = landscape.getModel().getSoftwareSystemWithName(softwareSystem.getName());
        if (softwareSystemInLandscape == null){
            softwareSystemInLandscape = landscape.getModel().addSoftwareSystem(softwareSystem.getName());
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

        setUrl(softwareSystemInLandscape, workspace.getId());

        boolean newRelations = findAndCloneRelationships(workspace, landscape);
        if (newRelations) {
            isDirty = true;
        }

        SystemLandscapeView landscapeView = landscape.getViews().getSystemLandscapeViews().stream().filter(lsView -> lsView.getKey().equals(LANDSCAPE_WORKSPACE_NAME)).findFirst().orElse(null);
        if (landscapeView == null) {
            landscapeView = landscape.getViews().createSystemLandscapeView(LANDSCAPE_WORKSPACE_NAME, "An automatically generated system landscape view.");
            isDirty = true;
        }

        if (isDirty || landscape.getLastModifiedDate() == null){
            landscape.setLastModifiedDate(new Date());
        }

        //TODO: Should be fine to run every time, but it is unclear what it actually accomplishes
        landscapeView.addAllElements();
    }

    public void setUrl(SoftwareSystem softwareSystem, Long workspaceId ){
        //softwareSystem.setUrl("{workspace:" + workspaceId + "}/diagrams#Containers");
        softwareSystem.setUrl(_apiConnection.url + "/share/" + workspaceId + "/diagrams#Containers");
    }

    public AdminApiClient createAdminApiClient() {
        return new AdminApiClient(_apiConnection.url + "/api", null, _apiConnection.apiKeyPlainText);
    }

    private WorkspaceApiClient createWorkspaceApiClient(WorkspaceMetadata workspaceMetadata) {
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
            workspace.getModel().addSoftwareSystem(name, description);
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

    /**
     * Clears all workspaces from internal collections
     */
    public void clear() {
        _workspacesByName.clear();
        _catalogWorkspacesByName.clear();
    }
}

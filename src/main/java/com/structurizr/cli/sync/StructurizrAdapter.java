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
    Map<String,Workspace> _hostedWorkspacesByName = new HashMap<>();
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
        _hostedWorkspacesByName.clear();
        _workspaceMetadataByName.clear();
        
        // Pull new data
        List<WorkspaceMetadata> workspaceMetadata = createAdminApiClient().getWorkspaces();
        for (WorkspaceMetadata metadata : workspaceMetadata) {
            _workspaceMetadataByName.put(metadata.getName().toLowerCase(), metadata);
            WorkspaceApiClient apiClient = createWorkspaceApiClient(metadata);
            apiClient.setMergeFromRemote(true);
            Workspace workspace = apiClient.getWorkspace(metadata.getId());
            _hostedWorkspacesByName.put(metadata.getName().toLowerCase(), workspace);
        }
    }

    public Workspace GetWorkspace(String name) {
        return _hostedWorkspacesByName.get(name.toLowerCase());
    }

    public Workspace GetCatalogWorkspace(String name) {
        return _catalogWorkspacesByName.get(name.toLowerCase());
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
        WorkspaceMetadata workspaceMetadata = _workspaceMetadataByName.get(nonCatalogWorkspace.getName().toLowerCase());
        String name = nonCatalogWorkspace.getName().toLowerCase();

        if (workspaceMetadata == null) {
            workspaceMetadata = createAdminApiClient().createWorkspace();
            System.out.println("Created workspace [" + workspaceMetadata.getId() + "] for [" + name +"]");
            _workspaceMetadataByName.put(name, workspaceMetadata);
            _hostedWorkspacesByName.put(name, nonCatalogWorkspace);
        }
        nonCatalogWorkspace.setId(workspaceMetadata.getId());

        Workspace catalogWorkspace = _catalogWorkspacesByName.get(name);
        if (catalogWorkspace == null) {
            catalogWorkspace = WorkspaceUtils.fromJson(WorkspaceUtils.toJson(nonCatalogWorkspace, false));
            _catalogWorkspacesByName.put(catalogWorkspace.getName().toLowerCase(), catalogWorkspace);
            catalogWorkspace.setId(workspaceMetadata.getId());
        }

        // If the names differ, it was a new workspace. Push it up so all is in sync.
        // Then repull metadata so we have the updated name.
        if (!catalogWorkspace.getName().equals(workspaceMetadata.getName())){
            WorkspaceApiClient workspaceApiClient = createWorkspaceApiClient(workspaceMetadata);
            System.out.println("Updating name of workspace id [" + workspaceMetadata.getId() + "] to [" + name +"] OnPrem");
            workspaceApiClient.putWorkspace(workspaceMetadata.getId(), catalogWorkspace);
            catalogWorkspace.setId(workspaceMetadata.getId());

            // update metadata so it has new name
            // also pulls all workspaces again, though it probably doesn't need to
            PullWorkspaces();
        }

        return catalogWorkspace;
    }

    public String WorkSpaceSnapshotForComparison(Workspace workspace)  throws Exception {
        if (workspace == null){
            return "";
        }

        Workspace tempWorkspace = WorkspaceUtils.fromJson(WorkspaceUtils.toJson(workspace, false));

        tempWorkspace.setLastModifiedDate(new Date(0L));
        tempWorkspace.setLastModifiedAgent("");
        tempWorkspace.setLastModifiedUser("");

        for (Relationship relationship: tempWorkspace.getModel().getRelationships()){
            relationship.addProperty(StructurizrAdapter.STRUCTURIZR_DSL_IDENTIFIER_PROPERTY_NAME, "x");
        }

        return  WorkspaceUtils.toJson(tempWorkspace, false);
    }

    public void PushWorkspaces(File baseWorkspacesFilePath) throws Exception, StructurizrClientException {
        for (WorkspaceMetadata workspaceMetadata: _workspaceMetadataByName.values()) {
            Workspace hostedWorkspace = _hostedWorkspacesByName.get(workspaceMetadata.getName().toLowerCase());
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

                String localWSString = WorkSpaceSnapshotForComparison(localDslWorkspace);
                String hostedWSString =  WorkSpaceSnapshotForComparison(hostedWorkspace);

                if (!localWSString.equals(hostedWSString)){
                    System.out.println("Workspace [" + workspaceMetadata.getName() + " - ID:" +
                            workspaceMetadata.getId()+"] differs from the hosted version. Pushing to OnPrem.");
                    WorkspaceApiClient workspaceApiClient = createWorkspaceApiClient(workspaceMetadata);
                    workspaceApiClient.setMergeFromRemote(false);
                    workspaceApiClient.putWorkspace(workspaceMetadata.getId(), localDslWorkspace);
                }
                else{
                    System.out.println("OnPrem and local [" + workspaceMetadata.getName() + "] do not differ and will not be pushed OnPrem.");
                }
            }
            else {
                System.out.println("The onPrem workspace [" + workspaceMetadata.getName() + "] does not appear to be managed in this repo.");
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
            systemName = systemName.toLowerCase();
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

        Workspace catalogWorkspace = _catalogWorkspacesByName.get(workspaceName.toLowerCase());
        if (catalogWorkspace != null) {
            Path path = Path.of(directoryPath);
            File catalogWorkspaceJson = new File(path.toFile(), "catalog-workspace.json");
            WorkspaceUtils.saveWorkspaceToJson(catalogWorkspace, catalogWorkspaceJson);

            saveWorkspaceDSL(path, catalogWorkspace);

            stubDocs(path, catalogWorkspace);
        }
    }

    /**
     * Creates initial documentation structure with templates
     *
     * @param path Base path where documentation should be created
     * @param workspace Name of the workspace for template customization
     * @throws Exception If an error occurs during file operations
     */
    private void stubDocs(Path path, Workspace workspace) throws Exception {
        String docsIndexTemplate = loadResourceAsString("/tokenizedWorkspaceDocIndex.md");
        String adrTemplate = loadResourceAsString("/0000-adr-template.md");
        String mkdocsTemplate = loadResourceAsString("/mkdocs-template.yaml");

        mkdocsTemplate = mkdocsTemplate
                .replace("{% system-name %}", workspace.getName())
                .replace("{% system-description %}", workspace.getDescription());

        Path mkdocsFile = path.resolve("mkdocs.yaml");
        if (!Files.exists(mkdocsFile)) {
            Files.writeString(
                    mkdocsFile,
                    mkdocsTemplate,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
            );
        }

        docsIndexTemplate = docsIndexTemplate
                .replace("{% system-name %}", workspace.getName())
                .replace("{% workspace-id %}", String.valueOf(workspace.getId()))
                .replace("{% system-url %}", DefaultUrl(workspace));

        Path docsPath = path.resolve("docs");
        Files.createDirectories(docsPath);

        Path adrPath = path.resolve("adrs");
        Files.createDirectories(adrPath);

        Path indexFile = docsPath.resolve("index.md");
        if (!Files.exists(indexFile)) {
            Files.writeString(
                    indexFile,
                    docsIndexTemplate,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
            );
        }

        Path adrTemplateFile = adrPath.resolve("0000-adr-template.md");
        if (!Files.exists(adrTemplateFile)) {
            Files.writeString(
                    adrTemplateFile,
                    adrTemplate,
                    java.nio.file.StandardOpenOption.CREATE,
                    java.nio.file.StandardOpenOption.TRUNCATE_EXISTING
            );
        }
    }

    private void saveWorkspaceDSL(Path path, Workspace workspace) throws Exception {
        File workspaceDslFile = new File(path.toFile(), "workspace.dsl");
        String landscapeDslTemplate = loadResourceAsString("/TokenizedLandscapeWorkspace.dsl");
        String systemDslTemplate = loadResourceAsString("/TokenizedSystemWorkspace.dsl");

        if (!workspaceDslFile.exists()) {
            System.out.println("New DSL file in " + path);
            String dslRendered = "";
            if (workspace.getConfiguration().getScope() == WorkspaceScope.SoftwareSystem) {
                SoftwareSystem system = workspace.getModel().getSoftwareSystemWithName(workspace.getName());
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

    public void addWorkspaceToCatalogLandscape(Workspace workspace, Workspace landscape) throws IllegalArgumentException, Exception {
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

        setPrimarySystemUrl(landscape);

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

    /**
     * Cross-adds systems from hosted workspaces to catalog workspaces.
     * For each system in the catalog workspaces, it examines all the hosted workspaces and adds any systems
     * from hosted workspaces to the catalog workspace if they don't already exist.
     * It also updates any newly included system tags from "idesign-mgr" to "idesign-resource-access".
     * The system with the same name as the workspace should keep the "idesign-mgr" tag.
     *
     * @throws Exception If an error occurs during system addition
     */
    public void crossAddSystemsToCatalog() throws Exception {
        // Iterate through each catalog workspace
        for (Map.Entry<String, Workspace> catalogEntry : _catalogWorkspacesByName.entrySet()) {
            String catalogWorkspaceName = catalogEntry.getKey();
            Workspace catalogWorkspace = catalogEntry.getValue();

            if (catalogWorkspace.getConfiguration().getScope() != WorkspaceScope.SoftwareSystem) {
                continue;
            }

            System.out.println("Adding remote systems to core workspace for use in cross-system diagramming: " + catalogWorkspaceName);

            String beforeCatalogWorkspaceString = WorkSpaceSnapshotForComparison(catalogWorkspace);

            for (Map.Entry<String, Workspace> hostedEntry : _hostedWorkspacesByName.entrySet()) {
                Workspace hostedWorkspace = hostedEntry.getValue();

                if (hostedWorkspace.getConfiguration().getScope() != WorkspaceScope.SoftwareSystem) {
                    continue;
                }

                // The primary system is named after the workspace
                SoftwareSystem hostedSystem = hostedWorkspace.getModel().getSoftwareSystemWithName(hostedWorkspace.getName());
                if (hostedSystem == null){
                    System.out.println("Existing workspace " + hostedWorkspace.getName() +
                            " does not have a system bearing its name and will not be added as a remote system to " + catalogWorkspaceName);
                }

                // Check if it already exists in the catalog workspace
                String hostedDSL = hostedSystem.getProperties().get(StructurizrAdapter.STRUCTURIZR_DSL_IDENTIFIER_PROPERTY_NAME);
                String hostedName = hostedSystem.getName();
                SoftwareSystem remoteSystem = catalogWorkspace.getModel().getSoftwareSystems().stream()
                        .filter(maybeExisting ->
                                hostedName.equalsIgnoreCase(maybeExisting.getName()) ||
                                        hostedDSL.equalsIgnoreCase( maybeExisting.getProperties().get(StructurizrAdapter.STRUCTURIZR_DSL_IDENTIFIER_PROPERTY_NAME)
                               ))
                        .findFirst()
                        .orElse(null);

                // upsert system from previous hosts
                if (remoteSystem == null) {
                    remoteSystem = catalogWorkspace.getModel().addSoftwareSystem(hostedName);

                    // System doesn't exist in the catalog workspace, so add it
                    System.out.println("  Adding system '" + hostedSystem.getName() + "' from hosted workspace '" +
                            hostedWorkspace.getName() + "' to catalog workspace '" + catalogWorkspaceName + "'");
                }

                remoteSystem.setDescription(hostedSystem.getDescription());

                Map<String, String> props = hostedSystem.getProperties();
                for (String key : props.keySet()) {
                    remoteSystem.addProperty(key, props.get(key));
                }

                for (Perspective perspective : hostedSystem.getPerspectives()) {
                    remoteSystem.addPerspective(perspective.getName(), perspective.getDescription());
                }

                remoteSystem.setGroup(hostedSystem.getGroup());

                // remote systems are considered resource-access in idesign, not mgrs
                List<String> newTags = new ArrayList<>();
                for (String tag : hostedSystem.getTagsAsSet()) {
                    // Replace idesign-mgr with idesign-resource-access for non-primary systems
                    if (tag.equals("idesign-mgr") && !hostedSystem.getName().equals(catalogWorkspaceName)) {
                        newTags.add("idesign-resource-access");
                    } else {
                        newTags.add(tag);
                    }
                }
                remoteSystem.addTags(newTags.toArray(new String[0]));

                remoteSystem.setUrl(hostedSystem.getUrl());
            }

            boolean newRelations = crossAddRelationships(catalogWorkspace);

            String afterCatalogWorkspaceString = WorkSpaceSnapshotForComparison(catalogWorkspace);

            // Update last modified date if changes were made
            if (!beforeCatalogWorkspaceString.equals(afterCatalogWorkspaceString)) {
                catalogWorkspace.setLastModifiedDate(new Date());
            }
        }
    }

    /**
     * Adds relationships between systems in a workspace
     *
     * @param workspace The workspace to update
     * @return true if any relationships were added
     */
    private boolean crossAddRelationships(Workspace workspace) {
        boolean changed = false;

        // Get all software systems in the workspace
        Collection<SoftwareSystem> systems = workspace.getModel().getSoftwareSystems();

        // For each hosted workspace, look for relationships between systems that exist in the catalog workspace
        for (Workspace hostedWorkspace : _hostedWorkspacesByName.values()) {
            for (Relationship relationship : hostedWorkspace.getModel().getRelationships()) {
                if (relationship.getSource() instanceof SoftwareSystem &&
                        relationship.getDestination() instanceof SoftwareSystem) {

                    String sourceName = relationship.getSource().getName();
                    String destName = relationship.getDestination().getName();

                    // Find corresponding systems in the catalog workspace
                    SoftwareSystem catalogSource = workspace.getModel().getSoftwareSystemWithName(sourceName);
                    SoftwareSystem catalogDest = workspace.getModel().getSoftwareSystemWithName(destName);

                    // If both systems exist and no relationship exists between them, create one
                    if (catalogSource != null && catalogDest != null &&
                            !catalogSource.hasEfferentRelationshipWith(catalogDest)) {

                        Relationship newRelationship = catalogSource.uses(catalogDest, relationship.getDescription());
                        newRelationship.addTags(relationship.getTags());

                        System.out.println("  Added relationship from '" + sourceName + "' to '" + destName +
                                "' in catalog workspace '" + workspace.getName() + "'");
                        changed = true;
                    }
                }
            }
        }

        return changed;
    }

    public String DefaultUrl(Workspace workspace){
        SoftwareSystem primarySoftwareSystem = workspace.getModel().getSoftwareSystemWithName(workspace.getName());
        if (primarySoftwareSystem != null){
            return _apiConnection.url + "/share/" + workspace.getId() + "/diagrams#Containers";
        }

       return null;
    }

    public void setPrimarySystemUrl(Workspace workspace){
        SoftwareSystem primarySoftwareSystem = workspace.getModel().getSoftwareSystemWithName(workspace.getName());
        if (primarySoftwareSystem != null){
            String url = DefaultUrl(workspace);
            primarySoftwareSystem.setUrl(url);
        }
    }

    public AdminApiClient createAdminApiClient() {
        return new AdminApiClient(_apiConnection.url + "/api", null, _apiConnection.apiKeyPlainText);
    }

    private WorkspaceApiClient createWorkspaceApiClient(WorkspaceMetadata workspaceMetadata) {
        WorkspaceApiClient workspaceApiClient = new WorkspaceApiClient(_apiConnection.url + "/api", workspaceMetadata.getApiKey(), workspaceMetadata.getApiSecret());
        workspaceApiClient.setWorkspaceArchiveLocation(null); // this prevents the local file system from being cluttered with JSON files

        return workspaceApiClient;
    }

    public Workspace createShellWorkspace(String name, String description, Collection<String> tags, String namespace, WorkspaceScope scope){
        Workspace workspace = new Workspace(name, description);
        workspace.getConfiguration().setScope(scope);
        workspace.getViews().getConfiguration().addTheme(IDESIGN_THEME_URL);
        workspace.getModel().addProperty(STRUCTURIZR_GROUP_SEPARATOR_PROPERTY_NAME, "/");

        if (scope == WorkspaceScope.SoftwareSystem){
            SoftwareSystem softwareSystem = workspace.getModel().addSoftwareSystem(name, description);

            if (tags != null && !tags.isEmpty()) {
                softwareSystem.addTags(tags.toArray(new String[0]));
            }

            softwareSystem.addProperty(BackstageAdapter.BACKSTAGE_REF_PROPERTY_NAME,
                    "system:" + namespace + "/" + softwareSystem.getName());

            softwareSystem.addProperty(StructurizrAdapter.STRUCTURIZR_DSL_IDENTIFIER_PROPERTY_NAME,
                    softwareSystem.getName().replaceAll("\\W", ""));

            setPrimarySystemUrl(workspace);
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
        _hostedWorkspacesByName.clear();
        _catalogWorkspacesByName.clear();
    }
}

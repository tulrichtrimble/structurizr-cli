package com.structurizr.cli.sync;

import com.structurizr.Workspace;
import com.structurizr.api.StructurizrClientException;
import com.structurizr.cli.AbstractCommand;
import com.structurizr.configuration.WorkspaceScope;
import com.structurizr.model.Container;
import com.structurizr.model.Element;
import com.structurizr.model.Relationship;
import com.structurizr.model.SoftwareSystem;
import com.structurizr.util.StringUtils;
import org.apache.commons.cli.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.util.Arrays;
import java.util.Date;
import java.util.Set;

public class SyncOnPremCommand extends AbstractCommand {

    private static final Log log = LogFactory.getLog(SyncOnPremCommand.class);

    @Override
    public void run(String... args) throws Exception , StructurizrClientException {
        Options options = new Options();

        Option option = new Option("url", "structurizrApiUrl", true, "The URL of the On Premesis instance to use for workspace identifiers and publishing");
        option.setRequired(true);
        options.addOption(option);

        option = new Option("key", "apiKey", true, "Workspace API key");
        option.setRequired(true);
        options.addOption(option);

        option = new Option("workspaces", "workspaces", true, "Folder to store named workspaces");
        option.setRequired(false);
        options.addOption(option);

        CommandLineParser commandLineParser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();

        ApiConnection apiConnection = null;

        String url = "";
        String key = "";
        String workspaceRoot = "";

        try{
            CommandLine cmd = commandLineParser.parse(options, args);
            url = cmd.getOptionValue("structurizrApiUrl", "https://arch-repo-fahxgzhxbqgbdmgt.centralus-01.azurewebsites.net");
            key = cmd.getOptionValue("apiKey", "TYLER_API_KEY");
            workspaceRoot = cmd.getOptionValue(
                    "workspaces",
                    System.getProperty("user.dir") + "\\..\\named-workspaces");

        }
        catch (ParseException e) {
            log.error(e.getMessage());
            formatter.printHelp("pull", options);

            System.exit(1);
        }

        apiConnection = new ApiConnection(url, key);
        StructurizrAdapter structurizrAdapter = new StructurizrAdapter(apiConnection);
        structurizrAdapter.PullWorkspaces();
        structurizrAdapter.PushWorkspaces(workspaceRoot);

        log.info("Pushing updaated workspaces to OnPrem " + url);

    }
}

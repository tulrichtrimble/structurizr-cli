package com.structurizr.cli.sync;

import com.structurizr.api.StructurizrClientException;
import com.structurizr.cli.AbstractCommand;
import com.structurizr.util.StringUtils;
import org.apache.commons.cli.*;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Date;
import java.util.Set;

public class SyncOnPremCommand extends AbstractCommand {

    private static final Log log = LogFactory.getLog(SyncOnPremCommand.class);

    @Override
    public void run(String... args) throws Exception , StructurizrClientException {
        Options options = new Options();

        Option option = new Option("url", "structurizrApiUrl", true, "The URL of the On Premises instance to use for workspace identifiers and publishing");
        option.setRequired(true);
        options.addOption(option);

        option = new Option("key", "apiKey", true, "Workspace API key");
        option.setRequired(true);
        options.addOption(option);

        option = new Option("workspaces", "workspaces", true, "Folder where named workspaces are stored");
        option.setRequired(false);
        options.addOption(option);

        CommandLineParser commandLineParser = new DefaultParser();
        HelpFormatter formatter = new HelpFormatter();

        try{
            ApiConnection apiConnection = null;
            String url = "";
            String key = "";
            String workspaceRoot = "";

            CommandLine cmd = commandLineParser.parse(options, args);
            url = cmd.getOptionValue("structurizrApiUrl", "https://arch-repo-fahxgzhxbqgbdmgt.centralus-01.azurewebsites.net");

            if (url.endsWith("/")) {
                url =  url.substring(0, url.length() - 1);
            }
            key = cmd.getOptionValue("apiKey", "TYLER_API_KEY");
            workspaceRoot = cmd.getOptionValue(
                    "workspaces",
                    System.getProperty("user.dir") + "\\..\\named-workspaces");

            File workspaceRootFolder = new File(workspaceRoot);
            if (workspaceRootFolder.exists() && workspaceRootFolder.isDirectory()){
                System.out.println("Loading local named workspaces from " + workspaceRoot);
            }
            else {
                System.out.println("The workspace path " + workspaceRoot + " is invalid.");
                return;
            }

            apiConnection = new ApiConnection(url, key);
            StructurizrAdapter structurizrAdapter = new StructurizrAdapter(apiConnection);
            structurizrAdapter.PullWorkspaces();
            structurizrAdapter.PushWorkspaces(workspaceRootFolder);
            log.info("Pushing updated workspaces to OnPrem " + url);
        }
        catch (ParseException e) {
            log.error(e.getMessage());
            formatter.printHelp("pull", options);

            System.exit(1);
        }
    }
}

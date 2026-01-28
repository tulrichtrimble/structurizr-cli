workspace extends {% workspace_path %} {

    // fully-qualify all names to prevent collisions
    //https://docs.structurizr.com/dsl/identifiers
    !identifiers hierarchical

    model {
        /***********************************************************************
        * Use !element to extend the model using DSL or canonical identifiers.
        ***********************************************************************/
        // [DSL]
        //!element softwareSystem1 {
        //     webapp1 = container "Web Application 1"
        // }
        // [canonical name]
        //!element "SoftwareSystem://Software System 1" {
        //     webapp2 = container "Web Application 2"
        // }

        !include https://raw.githubusercontent.com/tulrichtrimble/backstage-repository/main/personas.dsl

        !element {% system_dsl_name %} {
            !adrs /adrs
            !docs /docs

{% containers %}
        }

        physicalEnvironment = deploymentEnvironment "PhysicalEnvironment" {
            devGroup = deploymentGroup "Development"
            deploymentNode "DevSystemNode" " Physical Node" {
{% container_instances %}
            }
        }
    }
    
    views {
        container {% system_dsl_name %} Containers {
            include *
        }

        SystemContext {% system_dsl_name %} SystemContext {
            include *
        }

        systemLandscape AllSystems "All available systems"{
            include "element.type==SoftwareSystem"
            !impliedRelationships false
            autoLayout tb
        }

        deployment * physicalEnvironment physicalDeployments {
{% container_includes %}
            autoLayout tb
        }

        styles {
                element "Element" {
                    shape RoundedBox
                }
                element "Person" {
                    shape person
                }
            }
    }
}
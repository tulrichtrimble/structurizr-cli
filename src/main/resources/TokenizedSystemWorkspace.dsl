workspace extends {% workspace_path %} {

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

        !element {% system_dsl_name %} {
            !adrs /adrs
            !docs /docs

{% containers %}
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
            include *
            !impliedRelationships false
            autoLayout tb
        }
    }

}
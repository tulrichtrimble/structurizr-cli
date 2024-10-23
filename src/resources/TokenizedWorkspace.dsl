workspace extends {% workspace_path %} {

    model {
        /***********************************************************************
        * Use !element to extend the model using DSL or cannonical identifiers.
        ***********************************************************************/
        // [DSL]
        //!element softwareSystem1 {
        //     webapp1 = container "Web Application 1"
        // }
        // [canonical name]
        //!element "SoftwareSystem://Software System 1" {
        //     webapp2 = container "Web Application 2"
        // }
    }
    
    views {
        SystemContext iammanagementsystem SystemContext {
            include *
        }

        container iammanagementsystem Containers {
            include *
        }
    }

}
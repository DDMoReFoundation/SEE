#
# This script is executed in SEE home directory
# Pre-load libraries that we know will be required, primarily the ddmore package itself
library('ddmore')
library('xpose4')

# Additional initialisation specific to third party tools
.INIT_SCRIPT_NAME <- "r-console-init.R"
.runPluginInitScriptIfExists <- function(directory) {
    initScript <- file.path(getwd(),directory,.INIT_SCRIPT_NAME)
    result <- list(script = initScript)
    if(file.exists(initScript)) {
        result$exists <- TRUE
        result$loadStatus <- tryCatch({
            source(initScript)
            "SUCCESS"
        },
        error = function(err) { 
            paste0("FAILURE: ", err)
        })
    } else {
        result$exists <- FALSE
    }
    return(result)
}

.printPluginInitSummary <- function(pluginInitStatus) {
    msgs <- lapply(Filter(function(x){x$exists},pluginInitStatus), function(x) {
        paste0("* ", x$script, " initialization status: ", x$loadStatus,"\n") 
    })
    message(paste(replicate(80, "-"), collapse = ""))
    message("\nSEE Plugins' initialization scripts execution status\n ")
    if(length(msgs)>0) {
        message(paste0(msgs))
    } else {
        message("No additional R console initialization scripts were found in SEE installation\n")
    }
    message(paste(replicate(80, "-"), collapse = ""))
    message("\n")
}

.pluginInitStatus <- lapply(dir()[file.info(dir())[,"isdir"]],.runPluginInitScriptIfExists)
.printPluginInitSummary(.pluginInitStatus)

# Start the FIS and MIF/TES servers
ddmore:::DDMORE.setServer(createFISServer(startupScript=file.path(getwd(),"startup.bat")))
ddmore:::DDMORE.startServer(ddmore:::DDMORE.getServer())

# Housekeeping (clear workspace variables)
ignore=list(".MDLIDE_WORKSPACE_PATH")

.filterObjects <- function(objects.all, ignore) {
	objects.remove = lapply(objects.all, function(obj) {
				!(obj %in% ignore)
			})
	objects.all[unlist(objects.remove)]
}
# KEEP THIS AS THE LAST LINE OF THE SCRIPT
rm(list=.filterObjects(ls(all.names=TRUE),list(".MDLIDE_WORKSPACE_PATH")))

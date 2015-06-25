/*******************************************************************************
 * Copyright (C) 2015 Mango Solutions Ltd - All rights reserved.
 ******************************************************************************/
package eu.ddmore.see.tel

import static groovy.io.FileType.FILES

import org.apache.commons.io.FilenameUtils;
/**
 * This script is responsible for expanding Test Script Templates and generating separate TestScript for each MDL use case
 */
def testProjects = "./target/test-projects"
new File(testProjects).getAbsoluteFile().eachDir {
    def mdlFiles = []
    it.eachFileRecurse(FILES, {mdlFiles << it} )
    mdlFiles = mdlFiles.findAll {
        it.name =~ /.*\.mdl/
    }
    def templates = it.listFiles().findAll {
        it.name =~ /.*TestScriptTemplate\.R/
    }
    
    mdlFiles.each { mdlFile ->
        templates.each {
            def scriptFileName =  Eval.me("MODEL_NAME", FilenameUtils.getBaseName(mdlFile.getName()), "\"${it.getName()}\"").replaceAll("Template\\.R", ".R")
            def binding = ["MODEL_DIR":it.getParentFile().toPath().relativize(mdlFile.getParentFile().toPath()), "MODEL_FILE":mdlFile.getName()]
            def engine = new groovy.text.SimpleTemplateEngine()
            def template = engine.createTemplate(it.text).make(binding)
            new File(it.getParentFile(), scriptFileName).write(template.toString())
        }
    }
}

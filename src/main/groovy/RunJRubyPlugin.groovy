package com.smokejumperit.gradle

import org.gradle.api.*
import org.gradle.api.plugins.*
import org.apache.commons.lang.text.StrTokenizer as Toke
import org.apache.commons.lang.text.StrMatcher as Match

class RunJRubyPlugin extends SjitPlugin {
  void apply(Project project) {
    def pluginLogger = this.logger

    project.apply(plugin:ClassLoadersPlugin)
    project.apply(plugin:EnvPlugin)
    project.apply(plugin:ProjectExtPlugin)
    project.configurations { 
      jruby 
      jrubyPluginYaml
    }
    project.repositories {
      mavenCentral(name:"RunJRubyPluginMavenCentralRepo")
      mavenRepo(name:"${this.class.simpleName}SnakeYamlRepo", urls:"http://snakeyamlrepo.appspot.com/repository")
    }
    project.dependencies { 
      jruby "org.jruby:jruby-complete:1.6.0"
      jrubyPluginYaml "org.yaml:snakeyaml:1.6"
    }
    String[] defaultConfigs = ['jruby'] as String[]
    def splitCmd = { String cmd ->
      def toker = new Toke(cmd)
      toker.delimiterMatcher = Match.splitMatcher()
      toker.quoteMatcher = Match.quoteMatcher()
      return toker.tokenArray
    }


    def foundGemHome = {-> // Lazy thunk
      File gemHome = null
      return {->
        if(!gemHome) gemHome = findGemHome(project)
        return gemHome
      }
    }.call()
    project.metaClass.getGemHome = foundGemHome 
    project.metaClass.gemHome = { String gem -> 
      def dir = project.gemHome
      if(!dir.exists()) {
        pluginLogger.debug("$dir does not exist: cannot find gem $gem")
        return null
      }
      
      def gemDir = null
      dir.eachFileRecurse { 
        if(it.name =~ ('^' + gem + '-(?:\\.?\\d+)*$')) {
          if(!gemDir || it.name > gemDir.name) {
            gemDir = it
          }
        }
      }
			
			if(gemDir) {
				def relPath = project.tryRelativePath(gemDir)
				if(relPath instanceof File) {
					return relPath
				} else {
					return new File(relPath.toString())
				}
			} else {
				return null
			}
    }
    project.metaClass.gemScript = { String gem ->
      def dir = project.gemHome(gem)
      if(!dir.exists()) {
        pluginLogger.debug("$dir does not exist: cannot find home dir for $gem")
      }
  
      dir = new File(dir, "bin")
      if(!dir.exists()) {
        pluginLogger.debug("$dir does not exist: cannot find bin dir for $gem")
      }

      return project.tryRelativePath(new File(dir, gem))
    }

    project.metaClass.runJRuby = { String cmdArg, String[] configs=defaultConfigs ->
      def cmdArray = splitCmd("-I${project.gemHome} $cmdArg")
      pluginLogger.debug("JRuby Commands: ${cmdArray as List}")

			pluginLogger.debug("Loading JRuby main class")
      def JRuby = classFor('org.jruby.Main', configs)
			def JRuby_classLoader = JRuby.getClassLoader()

			pluginLogger.debug("Loading JRuby config class")
      def JRubyConfig = JRuby_classLoader.loadClass("org.jruby.RubyInstanceConfig")
			def rCfg = JRubyConfig.newInstance()
			rCfg.setHardExit(false)

      pluginLogger.info("Running JRuby: $cmdArg")
      def curThread = Thread.currentThread()
      curThread.setContextClassLoader(JRuby_classLoader)
      JRuby.getConstructor(JRubyConfig).newInstance(rCfg).run(cmdArray)
      curThread.setContextClassLoader(ClassLoader.systemClassLoader) // Allow JRuby to be GC'ed
      pluginLogger.debug("Done running JRuby: $cmdArg")

    }

    project.metaClass.useGem = { String gem ->
      if(!project.gemHome(gem)) {
        pluginLogger.info("Installing JRuby gem: $gem")
        project.runJRuby("-S gem install --no-rdoc --no-ri --install-dir '${project.gemHome}' $gem")
        pluginLogger.debug("Done installing JRuby gem: $gem")
        if(!project.gemHome(gem)) throw new Exception("Could not install $gem")
      } 
    }

  }

  File findGemHome(project) {
    File gemHome = new File(System.properties['user.home'], '.gem')
    logger.debug("Looking for Gem Home based on $project: defaulting gem home to $gemHome")
    if(project.env.GEM_HOME) {
      gemHome = new File(project.env.GEM_HOME ?: "${gemHome}")
      logger.debug("Found environment variable GEM_HOME=${project.env.GEM_HOME}: gem home is now $gemHome")
    }
    def homeGemRc = new File(System.properties['user.home'], '.gemrc')
    if(homeGemRc.exists()) {
      logger.debug("Found gem.rc at $homeGemRc")
      def Yaml = project.classFor("org.yaml.snakeyaml.Yaml", 'jrubyPluginYaml')
      homeGemRc.withInputStream { inStream ->
        Map gemrc = Yaml.load(inStream) as Map
        gemHome = new File(gemrc.gemhome ?: "${gemHome}")
      }
      logger.debug("Found gem.rc at $homeGemRc: gem home is now $gemHome")
    }
    return gemHome
  }

}

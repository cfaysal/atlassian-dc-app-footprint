import org.codehaus.groovy.control.CompilationUnit
import org.codehaus.groovy.control.CompilerConfiguration
import org.codehaus.groovy.control.Phases

CompilerConfiguration cfg = new CompilerConfiguration()
cfg.sourceEncoding = 'UTF-8'
CompilationUnit cu = new CompilationUnit(cfg)
cu.addSource(new File(args[0]))
cu.compile(Phases.CONVERSION)
println "PARSE OK  " + args[0] + "  classes=" + cu.getAST().getClasses().collect { it.name }.join(", ")

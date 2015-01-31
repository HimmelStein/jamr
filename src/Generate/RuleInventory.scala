package edu.cmu.lti.nlp.amr.Generate
import edu.cmu.lti.nlp.amr._

import scala.util.matching.Regex
import scala.collection.mutable.{Map, Set, ArrayBuffer}

class RuleInventory {

    val phraseTable : MultiMapCount[String, PhraseConceptPair] = new MultiMapCount()       // Map from concept to PhraseConcepPairs with counts
    val lexRules : MultiMapCount[String, Rule] = new MultiMapCount()            // Map from concept to lexicalized rules with counts
    val abstractRules : MultiMapCount[String, Rule] = new MultiMapCount()       // Map from pos to abstract rules with counts
    //val argTableLeft : Map[String, MultiMapCount[String, (String, String)]] = new MultiMapCount()  // Map from pos to map of args to realizations with counts
    //val argTableRight : Map[String, MultiMapCount[String, (String, String)]] = new MultiMapCount() // Map from pos to map of args to realizations with counts
    val argTableLeft : MultiMapCount[(String, String), (String, String)] = new MultiMapCount()  // Map from (pos, arg) to realizations with counts
    val argTableRight : MultiMapCount[(String, String), (String, String)] = new MultiMapCount() // Map from (pos, arg) to realizations with counts
    val argsLeft : Map[(String, String), Array[(String, String)]] = new Map()    // Todo: fill in (pos, arg) -> array of realizations
    val argsRight : Map[(String, String), Array[(String, String)]] = new Map()   // make sure there are no gaps
 
    def load(filename: String) {    // TODO: move to companion object
        phraseTable.readFile(filename+".phrasetable", x => x, PhraseConceptPair.apply_)
        lexRules.readFile(filename+".lexrules", x => x, Rule.apply_)
        abstractRules.readFile(filename+".abstractrules", x => x, Rule.apply_)
        createArgTables()
        createArgs()
    }

    def save(filename: String) {
        write_to_file(phraseTable.toString)
        // ...
    }

    def trainingIterator(corpus: Iterator[String], pos: Array[Annotation[Array[String]]]) : {
        // ...
    }

    def extractFromCorpus(corpus: Iterator[String], pos: Array[Annotation[Array[String]]]) { // TODO: move this constructor to companion object (and rename to fromCorpus)
        //val corpus = Source.fromFile(corpusFilename).getLines
        logger(0, "****** Extracting rules from the corpus *******")

        val dependencies: Array[String] = (for {
                block <- Corpus.splitOnNewline(dependencies)
            } yield block.replaceAllLiterally("-LRB-","(").replaceAllLiterally("-RRB-",")").replaceAllLiterally("""\/""","/")).toArray

        var i = 0
        for (block <- Corpus.getAMRBlocks(corpus)) {
            logger(0,"**** Processsing Block *****")
            logger(0,block)
            val data = AMRTrainingData(block)
            //val pos : Array[String] = dependencies(i).split("\n").map(x => x.split("\t")(4))
            val graph = data.toOracleGraph(clearUnalignedNodes = false)
            val sentence = data.sentence    // Tokenized sentence
            val spans : Map[String, (Option[Int], Option[Int])] = Map()     // stores the projected spans for each node
            val spanArray : Array[Boolean] = sentence.map(x => false)       // stores the endpoints of the spans
            computeSpans(graph, graph.root, spans, spanArray)
            //logger(0,"spanArray = "+spanArray.zip(sentence).toList.toString)
            logger(0,"****** Extracting rules ******")
            extractRules(graph, graph.root, sentence, pos, spans, spanArray, rules)
            logger(0,"****** Extracting phrase-concept pairs ******")
            extractPhraseConceptPairs(graph, sentence, pos)
            logger(0,"")
            i += 1
        }
        createArgTables()
        createArgs()
    }

    def getRealizations(node: Node) : List[(PhraseConceptPair, List[(String, Node)])] = {   // phrase, children not consumed
        return phraseTable.get.getOrElse(node.concept, List()).map(x => (x, node.children.map(y => (Label(x._1),x._2))))   // TODO: should produce a possible realization if not found
    }

    def getArgsLeft(pos_arg: (String, String)) : Array[(String, String)] = {    // Array[(left, right)]
        return argsLeft.getOrElse(new Array(("","")))   // (left, right), so ("", "") means no words to left or right
    }

    def getArgsRight(pos_arg: (String, String)) : Array[(String, String)] = {
        return argsRight.getOrElse(new Array(("","")))  // (left, right), so ("", "") means no words to left or right
    }

    private def createArgTables() {
        // Populates argTableLeft and argTableRight
        // Must call extractRules before calling this function
        for ((pos, rules) <- abstractRules.map) {
            for ((rule, count) <- rules if ruleOk(rule, count)) {
                for (x <- rule.left) {
                    val arg = rule.args(x._2)
                    argTableLeft.add((pos, arg) -> (x._1, x._3), count)
                }
                for (x <- rule.right) {
                    val arg = rule.args(x._2)
                    argTableRight.add((pos, arg) -> (x._1, x._3), count)
                }
            }
        }
    }

    private def createArgs() {
        // Populates argsLeft and argsRight
        // Must call createArgTables before calling this function
        for (((pos, arg), countMap) <- argTableLeft.map) {  // TODO: apply a filter on low count args?
            argsLeft((pos, arg)) = countMap.map(x => x._1).toArray
        }
        for (((pos, arg), countMap) <- argTableRight.map) { // TODO: apply a filter on low count args?
            argsRight((pos, arg)) = countMap.map(x => x._1).toArray
        }
    }

    private def ruleOk(rule : Rule, count: Int) : Boolean = {
        return count > 1    // TODO
    }

    private def extractPhraseConceptPairs(graph: Graph, sentence: Array[String], pos: Array[String]) {
        // Populates phraseTable
        for (span <- graph.spans) {
            phraseTable.add(span.amr.concept -> PhraseConceptPair(span, pos))
        }
    }

    private def extractRules(graph: Graph,
                     sentence: Array[String],
                     pos : Array[String],
                     spans: Map[String, (Option[Int], Option[Int])],    // map from nodeId to (start, end) in sent
                     spanArray: Array[Boolean]) {

        // Populates lexRules and abstractRules

        case class Child(label: String, node: Node, start: Int, end: Int)

        for (span <- graph.spans) {
            val node = graph.getNodeById(span.nodeIds.sorted.apply(0))
            val (ruleStart, ruleEnd) = spans(node.id)
            val children : List[Child] = node.children.filter(x => spans(x._2.id)._1 != None).filter(x => x._2.spans.spans(0) != node.spans(0)).map(x => {val (start, end) = spans(x._2.id); Child(x._1.drop(1).toUpperCase.replaceAll("-",""), x._2, start.get, end.get)}).sortBy(x => x.end)    // notice label => label.drop(1).toUpperCase.replaceAll("-","")
            //logger(1, "children = "+children.toString)
            if (children.size > 0 && !(0 until children.size-1).exists(i => children(i).start > children(i+1).end)) { // check for no overlapping child spans (if so, no rule can be extracted)
                var outsideLower = ruleStart.get
                //do { outsideLower -= 1 } while (outsideLower >= 0 && !spanArray(outsideLower))
                //outsideLower += 1
                var outsideUpper = ruleEnd.get
                //while (outsideUpper < sent.size && !spanArray(outsideUpper)) {
                //    outsideUpper += 1
                //}

                val args : List[Children] = children.sortBy(x => x.label)
                val lowerChildren : Vector[(Children, Int)] = args.zipWithIndex.filter(x => x._1.start < span.start).sortBy(_._1.start).toVector
                val upperChildren : Vector[(Children, Int)] = args.zipWithIndex.filter(x => x._1.start > span.end).sortBy(_._1.start).toVector
                val prefix : String = sentence.slice(outsideLower, ruleStart.get)
                val end : String = sentence.slice(myEnd.get, outsideUpper)
                val lex : String = sentence.slice(span.start, span.end).mkString(" ")
                val pos : String = pos.slice(span.start, span.end).mkString(" ")
                val headPos : String = pos.slice(span.end-1, span.end)

                val argsList = args.map(x => x.label).toVector
                var left = (0 until lowerChildren.size-1).map(
                    i => ("", x._2, sentence.slice(lowerChildren(i)._2.end, lowerChildren(i+1)._2.start))).toList
                left = left ::: List("", lowerChilren.last._2, sentence.slice(lowerChilren.last._1.end, span.start))
                var right = (1 until upperChildren.size).map(
                    i => (sentence.slice(upperChildren(i-1)._2.end, upperChildren(i)._2.start)), x._2, "").toList
                right = (sentence.slice(span.end, upperChilren.head._1.end), upperChilren.last._2, "") :: right
                val lhs = Rule.mkLhs(node, includeArgs=true)

                val rule = Rule(lhs, argsList, prefix, left, PhraseConceptPair(lex, span.amr.prettyString(0, false, Set.empty[String]), pos, headPos), right, end)
                lexRules.add(node.concept -> rule)

                val abstractRule = Rule(lhs, argsList, prefix, left, PhraseConceptPair("###", span.amr.prettyString(0, false, Set.empty[String]), pos, headPos), right, end)
                abstractRules.add(pos -> abstractRule)
            }
        }
    }

    private def computeSpans(graph: Graph, node: Node, spans: Map[String, (Option[Int], Option[Int])], spanArray: Array[Boolean]) : (Option[Int], Option[Int]) = {
        var myStart : Option[Int] = None
        var myEnd : Option[Int] = None
        if (node.spans.size > 0) {
            myStart = Some(graph.spans(node.spans(0)).start)
            myEnd = Some(graph.spans(node.spans(0)).end)
            spanArray(myStart.get) = true
            spanArray(myEnd.get - 1) = true
        }
        for ((_, child) <- node.topologicalOrdering) {
            val (start, end) = computeSpans(graph, child, spans, spanArray)
            if (myStart != None && myEnd != None) {
                if (start != None && end != None) {
                    myStart = Some(min(myStart.get, start.get))
                    myEnd = Some(max(myEnd.get, end.get))
                }
            } else {
                myStart = start
                myEnd = end
            }
        }
        spans(node.id) = (myStart, myEnd)
        return (myStart, myEnd)
    }

}

object RuleInventory/*(options: Map[Symbol, String])*/ {

    val usage = """Usage: scala -classpath . edu.cmu.lti.nlp.amr.Generate.ExtractSentenceRules --dependencies deps_file --corpus amr_file --decode data """
    type OptionMap = Map[Symbol, Any]

    def parseOptions(map : OptionMap, list: List[String]) : OptionMap = {
        def isSwitch(s : String) = (s(0) == '-')
        list match {
            case Nil => map
            case "-h" :: value :: tail =>                parseOptions(map ++ Map('help -> value.toInt), tail)
            case "-v" :: value :: tail =>                parseOptions(map ++ Map('verbosity -> value.toInt), tail)
            case "--corpus" :: value :: tail =>          parseOptions(map ++ Map('corpus -> value.toInt), tail)
            case "--decode" :: value :: tail =>          parseOptions(map ++ Map('decode -> value.toInt), tail)
            case "--dependencies" :: value :: tail =>    parseOptions(map + ('dependencies -> value), tail)
            case option :: tail => println("Error: Unknown option "+option) 
                               sys.exit(1) 
      }
    }

    def main(args: Array[String]) {
        val options = parseOptions(Map(),args.toList)
        if (options.contains('help)) { println(usage); sys.exit(1) }

        if (options.contains('verbosity)) {
            verbosity = options('verbosity).asInstanceOf[Int]
        }

        if (!options.contains('corpus)) { println("Must specify corpus file."); sys.exit(1) }
        if (!options.contains('decode)) { println("Must specify decode file."); sys.exit(1) }
        if (!options.contains('dependencies)) { println("Must specify dependencies file."); sys.exit(1) }

        val dependencies: Array[String] = (for {
                block <- Corpus.splitOnNewline(Source.fromFile(options('dependencies)).getLines())
            } yield block.replaceAllLiterally("-LRB-","(").replaceAllLiterally("-RRB-",")").replaceAllLiterally("""\/""","/")).toArray

        var i = 0
        for { block <- Corpus.splitOnNewline(Source.fromFile(options('corpus).getLines))
              if (block matches "(.|\n)*\n\\((.|\n)*") } {
            logger(0,"**** Processsing Block *****")
            logger(0,block)
            val data = AMRTrainingData(block)
            val pos : Array[String] = dependencies(i).split("\n").map(x => x.split("\t")(4))
            val graph = data.toOracleGraph(clearUnalignedNodes = false)
            val sentence = data.sentence    // Tokenized sentence
            val spans : Map[String, (Option[Int], Option[Int])] = Map()     // stores the projected spans for each node
            val spanArray : Array[Boolean] = sentence.map(x => false)       // stores the endpoints of the spans
            computeSpans(graph, graph.root, spans, spanArray)
            //logger(0,"spanArray = "+spanArray.zip(sentence).toList.toString)
            logger(0,"****** Extracted rules ******")
            extractRules(graph, graph.root, sentence, pos, spans, spanArray, rules)
            logger(0,"")
            i += 1
        }
    }

}


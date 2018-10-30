import scala.util.matching.Regex

object WebServer {
  val namedBlockRe = raw"""`{3}+\s?\w+\s+([\w\.\-\/_]+)?\s*(?:"(.+)")?\s*(+=)?$""".r
  val replaceRe = raw"^([\s]*)<<<(.+)>>>[\s]*$".r

  sealed trait CodeBlockType
  case class File(filename: String) extends CodeBlockType
  case class Block(id: String, filename: Option[String]) extends CodeBlockType

  /** Represents the 2-state state machine for scanning the markdown files. */
  sealed trait State {
    def blocks: Map[CodeBlockType, String]
  }

  case class NoBlockInProgressState (
    blocks: Map[CodeBlockType, String]
  ) extends State {
    def newBlock(blockType: CodeBlockType, appending: Boolean) = BlockInProgressState(
      blocks = blocks,
      blockType = blockType, 
      contents = "", 
      appending = appending
    )
  }

  class BlockInProgressState(
    blocks: Map[CodeBlockType, String], 
    blockType: CodeBlockType,
    contents: String,
    appending: Boolean
  ) extends State {
    def withNewLine(line: String) = new BlockInProgressState(
      blocks = blocks,
      contents = content + "\n" + line
    )

    def endBlock = {
      val newValue = if (blockInProgress.appending) {
        blocks.getOrElse(blockType, "") + blockInProgress.contents
      } else {
        blockInProgress.contents
      }

      NoBlockInProgressState(
        blocks = blocks + blockType -> newValue
      )
    }
  }


  def processFile(fileLines: Seq[String], blocks: Map[CodeBlockType, String]): Map[CodeBlockType, String] = {
    val finalState = lines.foldLeft(new NoBlockInProgressState(blocks)) { (state, line) =>
      state match {
        case state: BlockInProgressState if (line.trim == "```") => state.endBlock
        case state: BlockInProgressState => state.withNewLine(line)
        case state: NoBlockInProgressState =>
          namedBlockRe.findFirstMatchIn(line) match {
            case Some(m) =>
              val blockTypeOpt = (m.group(1), m.group(2)) match {
                case (null, null) => state
                case (fName, null) => state.newBlock(File(fName), m.group(3) == "+=")
                case (fNameOrNull, bId) => state.newBlock(bId, Option(fNameOrNull)), m.group(3) == "+=")
              }
            case _ => state
          }
      }
    }

    finalState.blocks
  }

  def main(args: Array[String]): Unit = {
    val workingDir = new java.io.File(System.getProperty("user.dir")) // https://bit.ly/2RLvY2W

    val blocks = args.foldLeft(Map.empty[CodeBlockType, String]) { (lastBlocks, fileName) =>
      processFile(scala.io.Source.fromFile(fileName).getLines(), lastBlocks)
    }

    val (fileBlocks, blockBlocks) = blocks.partition(_._1.isInstanceOf[File])

    for (
      (File(relativePath), contents) <- fileBlocks
    ) {
      createMissingDirectories(workingDir, relativePath)
      
    }
  }
}
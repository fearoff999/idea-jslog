import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.CommonDataKeys
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.editor.Caret
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.Editor
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.TextRange
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.codeStyle.CodeStyleManager
import java.util.ArrayList

data class ResultString(val resultString: String, val lastPosition: Int, val spaces: Int)

class LogVariable : AnAction() {

    private var editor: Editor? = null

    override fun actionPerformed(e: AnActionEvent) {
        this.editor = e.getData(CommonDataKeys.EDITOR)
        this.insertConsoleLog()
    }

    private fun getDocument(): Document? {
        return this.editor!!.document
    }

    private fun getProject(): Project? {
        return this.editor!!.project
    }

    private fun getPsiFile(): PsiFile? {
        val document = this.getDocument()
        val project = this.getProject()

        if (document is Document && project is Project) {
            // Get psi file for current document
            val psiFile = PsiDocumentManager.getInstance(project).getPsiFile(document)
            if (psiFile === null) {
                return null
            }

            // Check type of current file
            val supportedTypesArray = arrayOf("Vue.js", "JavaScript")
            val supportedTypes = listOf(*supportedTypesArray)
            if (!supportedTypes.contains(psiFile.fileType.name)) {
                return null
            }

            return psiFile
        }

        return null
    }

    private fun getCarets(): List<Caret>? {
        return this.editor!!.caretModel.allCarets
    }

    private fun getPsiElement(offset: Int): PsiElement? {
        try {
            var elementAt = this.getPsiFile()!!.findElementAt(offset)
            if (elementAt == null) {
                elementAt = this.getPsiFile()!!.findElementAt(offset - 1)
            }
            if (elementAt == null) {
                return null
            }
            if (elementAt.toString() != "PsiElement(JS:IDENTIFIER)") {
                elementAt = this.getPsiFile()!!.findElementAt(offset - 1)
            }
            return elementAt
        } catch (e: NullPointerException) {
            return null
        }
    }

    private fun getResultString(caret: Caret): ResultString? {
        val psiElement = this.getPsiElement(caret.offset)
        val stringBuilder = StringBuilder()
        if (psiElement === null) {
            return null
        }
        var lastPosition = caret.visualLineEnd
        val selectionModel = this.editor!!.selectionModel
        var spaces = this.getSpaces(selectionModel.selectionStart, selectionModel.selectionEnd)!!

        var element: String? = null
        val elementType = psiElement.toString()
        if (elementType == "PsiElement(JS:IDENTIFIER)") {
            element = psiElement.text
        }
        if (element === null) {
            return null
        }
        var parent: PsiElement? = psiElement.parent

        if (parent == null) {
            stringBuilder.insert(0, element)
            return ResultString(stringBuilder.toString(), lastPosition, spaces)
        }

        if (parent.toString() == "JSReferenceExpression") {
            element = parent.text
        }
        stringBuilder.insert(0, element)
        var depth = 0

        while (parent != null) {
            val name = this.getElementName(parent.toString())
            if (name != null) {
                if (stringBuilder.isNotEmpty()) {
                    stringBuilder.insert(0, " -> ")
                }
                stringBuilder.insert(0, name)
            }
            if (depth == 0) {
                when {
                    parent.toString().indexOf("JSVariable:") == 0 -> {
                        lastPosition = parent.textRange.endOffset + 1
                        spaces = this.getSpaces(parent.textRange.startOffset, parent.textRange.endOffset)!!
                    }
                    parent.toString() == "JSVarStatement" -> {
                        lastPosition = parent.textRange.endOffset + 1
                        spaces = this.getSpaces(parent.textRange.startOffset, parent.textRange.endOffset)!!
                    }
                    parent.toString().indexOf("JSFunctionProperty:") == 0 -> return null
                    parent.toString().indexOf("JSProperty:") == 0 -> return null
                }
            }

            parent = parent.parent
            depth++
        }

        stringBuilder.insert(0, "'")
        stringBuilder.append("', $element")

        return ResultString(stringBuilder.toString(), lastPosition, spaces)
    }

    private fun getSpaces(start: Int, end: Int): Int? {
        val charsRange = TextRange(start, end)
        val document = this.getDocument() ?: return 0
        val linesRange = TextRange(document.getLineNumber(charsRange.startOffset), document.getLineNumber(charsRange.endOffset))

        val linesBlock = TextRange(document.getLineStartOffset(linesRange.startOffset), document.getLineEndOffset(linesRange.endOffset))
        val lineText = document.getText(linesBlock)
        val lineLength = lineText.replace("^\\s+".toRegex(), "").length

        return linesBlock.length - lineLength
    }

    private fun getElementName(s: String): String? {
        val acceptedTypesArray = arrayOf("JSProperty", "JSFunctionProperty", "JSFunction", "ES6Class", "ES6Function", "JSFunctionExpression", "ES6FunctionProperty")
        val acceptedTypesList = listOf(*acceptedTypesArray)

        val parts = s.split(":".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        var name: String? = null

        if (parts.size > 1) {
            val type = parts[0]
            if (acceptedTypesList.contains(type)) {
                name = parts[1]
            }
        }
        return name
    }

    private fun insertConsoleLog() {
        val carets = this.getCarets()
        if (carets === null) {
            return
        }

        var stringsToInsert: ArrayList<String>? = null
        var lastRow: Int? = null
        var spaces: Int? = null

        carets.forEach { caret ->
            if (stringsToInsert == null) {
                stringsToInsert = ArrayList()
            }
            val resultString = this.getResultString(caret)
            if (resultString !== null) {
                stringsToInsert!!.add("console.log(${resultString.resultString});" + System.lineSeparator())
                lastRow = resultString.lastPosition
                spaces = resultString.spaces
            }
        }
        this.insertToNextLine(stringsToInsert, lastRow, spaces)
    }

    private fun insertToNextLine(stringsToInsert: ArrayList<String>?, lastRow: Int?, spaces: Int?) {
        val document = this.getDocument() ?: return
        val indentString = " ".repeat(spaces ?: 0)

        if (stringsToInsert === null || stringsToInsert.isEmpty()) {
            return
        }
        val project = this.getProject() ?: return
        val insertAction = WriteCommandAction.writeCommandAction(project)
        insertAction.run<Throwable> {
            stringsToInsert.forEach { text -> document.insertString(lastRow!!, System.lineSeparator() + indentString + text) }
            val insertedPsiElement = this.getPsiElement(lastRow!! + 1)
            if (insertedPsiElement !== null) {
                CodeStyleManager.getInstance(project).reformat(insertedPsiElement)
            }
        }
    }
}
package cn.therouter.idea.navigator

import com.intellij.codeInsight.daemon.LineMarkerInfo
import com.intellij.codeInsight.navigation.NavigationGutterIconBuilder
import com.intellij.openapi.editor.markup.GutterIconRenderer
import com.intellij.openapi.progress.util.BackgroundTaskUtil
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiManager
import com.intellij.psi.search.FilenameIndex
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.psi.util.PsiTreeUtil
import java.util.concurrent.CopyOnWriteArrayList

/**
 * mainly copy from LineMarkerUtils3
 */
class LineMarkerUtils5 : LineMarkerFunction {

    // 行标记跳转的目标代码
    private val allTargetPsi = HashMap<String, HashSet<TargetPsiElement>>()

    // 需要加行标记的代码
    private val allMarkerPsi = HashMap<String, CodeWrapper>()

    override fun main(elements: MutableList<out PsiElement>): ArrayList<LineMarkerInfo<*>> {
        return create(elements)
    }

    private fun create(elements: MutableList<out PsiElement>): ArrayList<LineMarkerInfo<*>> {
        val result = ArrayList<LineMarkerInfo<*>>()
        val filePath = elements[0].containingFile.viewProvider.virtualFile.canonicalPath
        val proj = elements[0].project
        val scope = GlobalSearchScope.projectScope(proj)
        if (allCodeFiles.isEmpty() || MyVFSListener.isChanged.also { MyVFSListener.isChanged = false }) {
            if (allCodeFiles.isNotEmpty()) {
                allCodeFiles.clear()
            }
            allCodeFiles.addAll(FilenameIndex.getAllFilesByExt(proj, "kt", scope))
            allCodeFiles.addAll(FilenameIndex.getAllFilesByExt(proj, "java", scope))
        }
        elements.forEach { psiElement ->
            try {
                findCode(psiElement)?.let { targetContent ->
                    try {
                        val key = getKey(filePath, psiElement)
                        allMarkerPsi[key] = targetContent
                        val targetPsiSet = allTargetPsi[key] ?: HashSet()
                        val list = findAllTargetPsi(proj, filePath, targetContent)
                        list.forEach { newFind ->
                            var exists = false
                            targetPsiSet.forEach { target ->
                                if (newFind.text == target.text) {
                                    exists = true
                                }
                            }
                            if (!exists) {
                                targetPsiSet.add(newFind)
                            }
                        }
                        allTargetPsi[key] = targetPsiSet
                    } catch (_: Exception) {
                    }
                }
            } catch (_: Exception) {
            }
        }

        elements.forEach { element ->
            val key = getKey(filePath, element)
            allMarkerPsi[key]?.let { targetContent ->
                val all = allTargetPsi[key] ?: HashSet()
                if (all.isNotEmpty()) {
                    val builder = NavigationGutterIconBuilder.create(getIcon(targetContent.type))
                        .setAlignment(GutterIconRenderer.Alignment.CENTER)
                        .setTargets(all)
                    if (targetContent.type == TYPE_ROUTE_ANNOTATION || targetContent.type == TYPE_ACTION_INTERCEPT) {
                        builder.setTooltipTitle("跳转到使用处")
                    } else {
                        builder.setTooltipTitle("跳转到声明处")
                    }
                    result.add(builder.createLineMarkerInfo(targetContent.psiElement))
                } else {
                    val builder = NavigationGutterIconBuilder.create(getIcon(TYPE_NONE))
                        .setAlignment(GutterIconRenderer.Alignment.CENTER)
                        .setTargets(all)
                    if (targetContent.type == TYPE_ROUTE_ANNOTATION || targetContent.type == TYPE_ACTION_INTERCEPT) {
                        builder.setTooltipTitle("未发现使用:TheRouter.build(${targetContent.code})")
                    } else {
                        val path = targetContent.code
                        if (!path.contains('"') && !path.isFirstUpper()) {
                            builder.setTooltipTitle("变量 ${targetContent.code} 内容未知，请开发者检查是否定义路由")
                        } else {
                            if (targetContent.psiElement.text.contains("action(")) {
                                builder.setTooltipTitle("未定义 ActionInterceptor(${targetContent.code})")
                            } else {
                                builder.setTooltipTitle("未声明 @Route(path=${targetContent.code})")
                            }
                        }
                    }
                    result.add(builder.createLineMarkerInfo(targetContent.psiElement))
                }
            }
        }
        return result
    }

    /**
     * 查找当前psi是否为TheRouter相关API
     */
    private fun findCode(psiElement: PsiElement): CodeWrapper? {
        var target = getRouteAnnotationCode(psiElement)
        if (target == null) {
            target = getActionInterceptorCode(psiElement)
        }
        if (target == null) {
            target = getTheRouterBuildCode(psiElement)
        }
        return target
    }

    private val allCodeFiles = ArrayList<VirtualFile>()

    /**
     * 查找入参 targetContent，能跳转到的目标代码
     */
    private fun findAllTargetPsi(
        project: Project,
        filePath: String?,
        content: CodeWrapper
    ): Collection<TargetPsiElement> {
        val result = HashSet<TargetPsiElement>()
        val psiManager = PsiManager.getInstance(project)
        for (virtualFile in allCodeFiles) {
            if (virtualFile.canonicalPath == filePath) {
                continue
            }
            val file = psiManager.findFile(virtualFile)
            file?.let { psiFile ->
                val properties = PsiTreeUtil.findChildrenOfType(psiFile, PsiElement::class.java)
                properties.forEach { psiElement ->
                    // 根据当前content类型，查找需要跳转的目标代码
                    when (content.type) {
                        TYPE_ROUTE_ANNOTATION -> {
                            if (isTheRouterBuild(psiElement, content.code)) {
                                result.add(TargetPsiElement(psiElement))
                            }
                        }

                        TYPE_THEROUTER_BUILD -> {
                            if (isRouteAnnotation(psiElement, content.code)) {
                                result.add(TargetPsiElement(psiElement))
                            } else if (isTheRouterAddActionInterceptor(psiElement, content.code)) {
                                result.add(TargetPsiElement(psiElement))
                            }
                        }

                        TYPE_ACTION_INTERCEPT -> {
                            if (isTheRouterBuild(psiElement, content.code)) {
                                result.add(TargetPsiElement(psiElement))
                            }
                        }
                    }
                }
            }
        }
        return result
    }

    private fun getKey(filePath: String?, psiElement: PsiElement) = filePath + TargetPsiElement(psiElement).getKey()
}

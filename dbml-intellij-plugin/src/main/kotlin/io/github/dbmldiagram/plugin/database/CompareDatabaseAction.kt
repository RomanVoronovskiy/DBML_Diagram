package io.github.dbmldiagram.plugin.database

import com.intellij.openapi.actionSystem.*

class CompareDatabaseAction : AnAction() {
    override fun getActionUpdateThread()=ActionUpdateThread.BGT
    override fun update(event: AnActionEvent) {
        event.presentation.isEnabledAndVisible=event.project!=null && event.getData(CommonDataKeys.VIRTUAL_FILE)?.extension.equals("dbml",true)
    }
    override fun actionPerformed(event: AnActionEvent) {
        val project=event.project ?: return
        val file=event.getData(CommonDataKeys.VIRTUAL_FILE) ?: return
        DatabaseComparisonService.get(project).connectAndCompare(file)
    }
}

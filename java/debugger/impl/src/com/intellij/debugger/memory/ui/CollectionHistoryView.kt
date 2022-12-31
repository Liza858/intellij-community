// Copyright 2000-2022 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license.
package com.intellij.debugger.memory.ui

import com.intellij.debugger.engine.*
import com.intellij.debugger.engine.evaluation.EvaluationContext
import com.intellij.debugger.engine.evaluation.EvaluationContextImpl
import com.intellij.debugger.engine.events.DebuggerCommandImpl
import com.intellij.debugger.impl.DebuggerUtilsImpl
import com.intellij.debugger.impl.DebuggerUtilsImpl.getValueMarkers
import com.intellij.debugger.jdi.VirtualMachineProxyImpl
import com.intellij.debugger.memory.utils.InstanceJavaValue
import com.intellij.debugger.memory.utils.InstanceValueDescriptor
import com.intellij.debugger.memory.utils.StackFrameItem
import com.intellij.debugger.ui.impl.watch.DebuggerTreeNodeImpl
import com.intellij.debugger.ui.impl.watch.MessageDescriptor
import com.intellij.debugger.ui.impl.watch.NodeDescriptorProvider
import com.intellij.debugger.ui.impl.watch.NodeManagerImpl
import com.intellij.debugger.ui.tree.NodeDescriptor
import com.intellij.debugger.ui.tree.ValueDescriptor
import com.intellij.debugger.ui.tree.render.ChildrenBuilder
import com.intellij.debugger.ui.tree.render.DescriptorLabelListener
import com.intellij.debugger.ui.tree.render.NodeRenderer
import com.intellij.debugger.ui.tree.render.NodeRendererImpl
import com.intellij.icons.AllIcons
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import com.intellij.psi.JavaPsiFacade
import com.intellij.psi.PsiField
import com.intellij.psi.PsiModifier
import com.intellij.psi.search.GlobalSearchScope
import com.intellij.ui.JBSplitter
import com.intellij.ui.components.JBScrollPane
import com.intellij.xdebugger.frame.XValueChildrenList
import com.intellij.xdebugger.impl.ui.tree.nodes.XValueNodeImpl
import com.intellij.xdebugger.memory.ui.InstancesTree
import com.sun.jdi.ObjectReference
import com.sun.jdi.ReferenceType
import com.sun.jdi.Type
import com.sun.jdi.Value
import org.jetbrains.annotations.ApiStatus
import java.util.concurrent.CompletableFuture
import javax.swing.Icon
import javax.swing.JComponent
import javax.swing.event.TreeSelectionListener

@ApiStatus.Experimental
class CollectionHistoryView(private val myFieldParentClsName: String,
                            private val myFieldName: String,
                            debugProcess: JavaDebugProcess,
                            private val myCollectionNode: XValueNodeImpl?) {
  private val SPLITTER_PROPORTION = 0.3f
  private val MAX_INSTANCES_NUMBER: Long = 0
  private val myDebugProcess = debugProcess.debuggerSession.process
  private val myDebugSession = debugProcess.session
  private val myNodeManager = MyNodeManager(myDebugSession.project)
  private val myHistoryRenderer = CollectionHistoryRenderer()
  private val myFieldParentJVMClsName = tryGetJVMClsName(myFieldParentClsName)
  private val myFieldParents = InstancesTree(myDebugProcess.project, myDebugSession.debugProcess.editorsProvider,
                                             getValueMarkers(myDebugProcess)) { }
  private val myFieldModifiers = findPsiField()?.modifierList
  private val myFieldHistoryPanel = FieldHistoryPanel(myCollectionNode?.parent as? XValueNodeImpl)
  private val myMainComponent: JComponent

  init {
    val shouldLoadFieldParents = myCollectionNode == null && !fieldIsStatic()
    if (shouldLoadFieldParents) {
      setupFieldParentsTree()
      myMainComponent = JBSplitter(false, SPLITTER_PROPORTION)
      myMainComponent.setHonorComponentsMinimumSize(false)
      myMainComponent.firstComponent = JBScrollPane(myFieldParents)
      myMainComponent.secondComponent = myFieldHistoryPanel.getComponent()
    }
    else {
      myMainComponent = myFieldHistoryPanel.getComponent()
    }
  }

  fun getComponent(): JComponent {
    return myMainComponent
  }

  private fun findPsiField(): PsiField? {
    return JavaPsiFacade.getInstance(myDebugSession.project).findClass(myFieldParentClsName,
                                                                       GlobalSearchScope.allScope(myDebugSession.project))?.findFieldByName(
      myFieldName, false)
  }

  private fun tryGetJVMClsName(clsName: String): String? {
    val psiClass = JavaPsiFacade.getInstance(myDebugSession.project).findClass(clsName, GlobalSearchScope.allScope(myDebugSession.project))
    return if (psiClass == null) null else JVMNameUtil.getClassVMName(psiClass)
  }

  private fun fieldIsStatic(): Boolean {
    return myFieldModifiers?.hasModifierProperty(PsiModifier.STATIC) ?: false
  }

  private fun invokeInDebuggerThread(runnable: () -> Unit) {
    myDebugProcess.managerThread.schedule(object : DebuggerCommandImpl() {
      override fun action() {
        runnable()
      }
    })
  }

  private fun invokeInDebuggerThreadAndWait(runnable: () -> Unit) {
    myDebugProcess.managerThread.invokeAndWait(object : DebuggerCommandImpl() {
      override fun action() {
        runnable()
      }
    })
  }

  private fun jvmClsNameToJavaClsName(jvmName: String): String {
    return jvmName.replace("$", ".")
  }

  private fun getBaseClasses(vm: VirtualMachineProxyImpl): List<ReferenceType> {
    return if (myFieldParentJVMClsName != null) {
      vm.classesByName(myFieldParentJVMClsName)
    }
    else {
      vm.allClasses().filter {
        jvmClsNameToJavaClsName(it.name()) == myFieldParentClsName
      }
    }
  }

  private fun getFieldParents(vm: VirtualMachineProxyImpl): List<Value> {
    val baseClasses = getBaseClasses(vm)
    return vm.allClasses().filter { cls ->
      baseClasses.any { baseCls ->
        DebuggerUtilsImpl.instanceOf(cls, baseCls)
      }
    }.flatMap { it.instances(MAX_INSTANCES_NUMBER) }
  }

  private fun setupFieldParentsTree() {
    myFieldParents.addTreeSelectionListener(TreeSelectionListener {
      val selectionPath = it.path
      val fieldParentNode = selectionPath?.lastPathComponent as? XValueNodeImpl ?: return@TreeSelectionListener
      myFieldHistoryPanel.loadHistory(fieldParentNode)
    })

    myFieldParents.addChildren(createChildren(listOf(), null), true)
    invokeInDebuggerThread {
      val vm = getVirtualMachine() ?: return@invokeInDebuggerThread
      val fieldParents = getFieldParents(vm)
      invokeLater {
        myFieldParents.addChildren(createChildren(fieldParents, null), true)
      }
    }
  }

  private fun createChildren(values: List<Value>, renderer: NodeRenderer?): XValueChildrenList {
    val children = XValueChildrenList()
    invokeInDebuggerThreadAndWait {
      val suspendContext = getSuspendContext() ?: return@invokeInDebuggerThreadAndWait
      for (value in values) {
        val ref = value as? ObjectReference ?: continue
        val evalContext = EvaluationContextImpl(suspendContext, suspendContext.frameProxy)
        val descriptor = JavaReferenceInfo(ref).createDescriptor(myDebugProcess.project)
        val javaValue = InstanceJavaValue(descriptor, evalContext, myNodeManager)
        renderer?.let { javaValue.descriptor.setRenderer(it) }
        children.add(javaValue)
      }
    }
    return children
  }

  private fun invokeLater(runnable: () -> Unit) {
    ApplicationManager.getApplication().invokeLater(runnable)
  }

  private fun getVirtualMachine(): VirtualMachineProxyImpl? {
    DebuggerManagerThreadImpl.assertIsManagerThread()
    return getSuspendContext()?.frameProxy?.virtualMachine
  }

  private fun getSuspendContext(): SuspendContextImpl? {
    return myDebugProcess.suspendManager.pausedContext
  }

  private class MyNodeManager(project: Project?) : NodeManagerImpl(project, null) {
    override fun createNode(descriptor: NodeDescriptor, evaluationContext: EvaluationContext): DebuggerTreeNodeImpl {
      return DebuggerTreeNodeImpl(null, descriptor)
    }

    override fun createMessageNode(descriptor: MessageDescriptor): DebuggerTreeNodeImpl {
      return DebuggerTreeNodeImpl(null, descriptor)
    }

    override fun createMessageNode(message: String): DebuggerTreeNodeImpl {
      return DebuggerTreeNodeImpl(null, MessageDescriptor(message))
    }
  }

  private inner class CollectionHistoryRenderer : NodeRendererImpl("Collection History", true) {
    init {
      setIsApplicableChecker { CompletableFuture.completedFuture(true) }
    }

    override fun getUniqueId(): String = "CollectionHistoryRenderer"

    override fun isApplicable(type: Type?) = throw IllegalStateException("Should not be called")

    override fun isExpandableAsync(value: Value?,
                                   evaluationContext: EvaluationContext?,
                                   parentDescriptor: NodeDescriptor?): CompletableFuture<Boolean> = CompletableFuture.completedFuture(true)

    override fun calcLabel(descriptor: ValueDescriptor?,
                           evaluationContext: EvaluationContext?,
                           labelListener: DescriptorLabelListener?): String = ""

    override fun buildChildren(value: Value?, builder: ChildrenBuilder?, evaluationContext: EvaluationContext?) {
      if (evaluationContext == null || builder == null) return
      val collectionInstance = value as? ObjectReference ?: return
      val suspendContext = getSuspendContext() ?: return

      val collectionModifications = CollectionBreakpointUtils.getCollectionModificationsHistory(suspendContext, collectionInstance)

      val nodes = collectionModifications
        .filterIsInstance<ObjectReference>().mapNotNull {
          CollectionBreakpointUtils.getCollectionModificationInfo(myDebugProcess, evaluationContext, it)
        }.map {
        val element = it.first
        val isAddition = it.second.value()
        val descriptor = ModificationInfoDescriptor(myDebugSession.project, element, isAddition)
        myNodeManager.createNode(descriptor, evaluationContext)
      }

      builder.setChildren(nodes)
    }

    private inner class ModificationInfoDescriptor(project: Project,
                                                   element: Value,
                                                   private val isAddition: Boolean) : InstanceValueDescriptor(project, element) {
      override fun getValueIcon(): Icon {
        return if (isAddition) AllIcons.General.Add else AllIcons.General.Remove
      }
    }
  }

  private inner class FieldHistoryPanel(private var myFieldParentNode: XValueNodeImpl?) {
    private val SPLITTER_PROPORTION = 0.5f
    private val myFieldHistory = InstancesTree(myDebugProcess.project, myDebugSession.debugProcess.editorsProvider,
                                               getValueMarkers(myDebugProcess)) { }
    private val myStackFrameList = StackFrameList(myDebugProcess)
    private val myComponent: JComponent

    init {
      setupFieldHistoryTree()
      loadFieldHistory()
      myComponent = JBSplitter(false, SPLITTER_PROPORTION)
      myComponent.firstComponent = JBScrollPane(myFieldHistory)
      myComponent.secondComponent = JBScrollPane(myStackFrameList)
    }

    fun loadHistory(fieldParentNode: XValueNodeImpl) {
      myFieldParentNode = fieldParentNode
      loadFieldHistory()
    }

    private fun setupFieldHistoryTree() {
      myFieldHistory.addTreeSelectionListener(TreeSelectionListener {
        myStackFrameList.clear()
        val selectedNode = getSelectedNode() ?: return@TreeSelectionListener
        val parentNode = selectedNode.parent as? XValueNodeImpl
        invokeInDebuggerThread {
          val frameItems = getStackFrameItems(selectedNode, parentNode) ?: return@invokeInDebuggerThread
          invokeLater { myStackFrameList.setFrameItems(frameItems) }
        }
      })
    }

    private fun getStackFrameItems(selectedNode: XValueNodeImpl, parentNode: XValueNodeImpl?): List<StackFrameItem>? {
      val vm = getVirtualMachine() ?: return null
      val suspendContext = getSuspendContext() ?: return null

      val frameItems = if (parentNode === myFieldHistory.root) {
        getFieldModificationStack(suspendContext, vm, selectedNode)
      }
      else {
        getCollectionModificationStack(suspendContext, vm, selectedNode, parentNode)
      }
      return frameItems
    }

    private fun getFieldModificationStack(suspendContext: SuspendContextImpl,
                                          vm: VirtualMachineProxyImpl,
                                          selectedNode: XValueNodeImpl): List<StackFrameItem>? {
      val selectedRow = myFieldHistory.root?.children?.indexOf(selectedNode)
      if (selectedRow == null || selectedRow == -1) return null
      val clsInstance = getObjectReferenceForNode(myFieldParentNode)
      val modificationIndex = vm.mirrorOf(selectedRow)
      val jvmClsName = tryGetJVMClsName(vm) ?: return null
      return CollectionBreakpointUtils.getFieldModificationStack(suspendContext, myFieldName, jvmClsName, clsInstance, modificationIndex)
    }

    private fun getCollectionModificationStack(suspendContext: SuspendContextImpl,
                                               vm: VirtualMachineProxyImpl,
                                               selectedNode: XValueNodeImpl,
                                               parentNode: XValueNodeImpl?): List<StackFrameItem>? {
      val selectedRow = parentNode?.children?.indexOf(selectedNode)
      if (selectedRow == null || selectedRow == -1) return null
      val modificationIndex = vm.mirrorOf(selectedRow)
      val collectionInstance = getObjectReferenceForNode(parentNode) ?: return null
      return CollectionBreakpointUtils.getCollectionModificationStack(suspendContext, collectionInstance, modificationIndex)
    }

    private fun clearHistory() {
      myFieldHistory.addChildren(createChildren(listOf(), myHistoryRenderer), true)
      myFieldHistory.rebuildTree(InstancesTree.RebuildPolicy.RELOAD_INSTANCES)
      myStackFrameList.clear()
    }

    private fun tryGetJVMClsName(vm: VirtualMachineProxyImpl): String? {
      DebuggerManagerThreadImpl.assertIsManagerThread()
      if (myFieldParentJVMClsName != null) return myFieldParentJVMClsName
      return vm.allClasses().firstOrNull {
        jvmClsNameToJavaClsName(it.name()) == myFieldParentClsName
      }?.name()
    }

    private fun getSelectedNode(): XValueNodeImpl? {
      val selectionPath = myFieldHistory.selectionPath
      return selectionPath?.lastPathComponent as? XValueNodeImpl ?: return null
    }

    private fun getObjectReferenceForNode(node: XValueNodeImpl?): ObjectReference? {
      val descriptor = node?.valueContainer as? NodeDescriptorProvider ?: return null
      return (descriptor.descriptor as? ValueDescriptor)?.value as? ObjectReference
    }

    private fun getSuspendContext(): SuspendContextImpl? {
      return myDebugProcess.suspendManager.pausedContext
    }

    private fun loadFieldHistory(parent: XValueNodeImpl?) {
      val clsInstance = getObjectReferenceForNode(parent)
      val suspendContext = getSuspendContext() ?: return
      val vm = getVirtualMachine() ?: return
      val jvmClsName = tryGetJVMClsName(vm) ?: return
      val fieldModifications = CollectionBreakpointUtils.getFieldModificationsHistory(suspendContext, myFieldName, jvmClsName, clsInstance)
      invokeLater {
        myFieldHistory.addChildren(createChildren(fieldModifications, myHistoryRenderer), true)
      }
    }

    private fun loadFieldHistory() {
      clearHistory()
      invokeInDebuggerThread { loadFieldHistory(myFieldParentNode) }
    }

    fun getComponent(): JComponent {
      return myComponent
    }
  }
}
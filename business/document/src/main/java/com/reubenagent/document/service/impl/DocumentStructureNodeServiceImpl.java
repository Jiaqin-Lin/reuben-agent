package com.reubenagent.document.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.reubenagent.document.entity.DocumentStructureNode;
import com.reubenagent.document.mapper.IDocumentStructureNodeMapper;
import com.reubenagent.document.model.DocumentIntermediateStructureNode;
import com.reubenagent.document.service.IDocumentStructureNodeService;
import com.reubenagent.framework.uid.UidGenerator;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 文档结构节点服务实现 —— 管理解析管线产出的树形结构节点。
 *
 * <p>{@link #saveNodes} 采用两遍遍历策略：</p>
 * <ol>
 *   <li><b>预分配 ID</b> —— 遍历候选节点，为每个有效 nodeNo 分配雪花 ID，
 *       构建 {@code Map<Integer, Long>} 映射表</li>
 *   <li><b>组装实体</b> —— 再次遍历候选节点，通过 ID 映射表将
 *       parentNodeNo / prevSiblingNodeNo / nextSiblingNodeNo 转换为数据库 ID，
 *       逐条 INSERT</li>
 * </ol>
 *
 * <p>输入为管线中间节点 {@link DocumentIntermediateStructureNode}（已含完整的
 * 父子/兄弟关系），无需额外的 Candidate 中间层，减少对象转换。</p>
 *
 * @author reuben
 * @since 2026-06-19
 */
@Slf4j
@AllArgsConstructor
@Service
public class DocumentStructureNodeServiceImpl implements IDocumentStructureNodeService {

    private final IDocumentStructureNodeMapper structureNodeMapper;
    private final UidGenerator uidGenerator;

    @Override
    public List<DocumentStructureNode> saveNodes(Long documentId,
                                                  Long parseTaskId,
                                                  List<DocumentIntermediateStructureNode> nodes) {
        deleteByDocumentId(documentId);

        if (documentId == null || parseTaskId == null || nodes == null || nodes.isEmpty()) {
            return List.of();
        }

        // 第一遍：为每个有效 nodeNo 预分配雪花 ID
        Map<Integer, Long> nodeIdMap = new LinkedHashMap<>();
        for (DocumentIntermediateStructureNode node : nodes) {
            if (node == null || node.getNodeNo() == null) {
                continue;
            }
            nodeIdMap.put(node.getNodeNo(), uidGenerator.getUid());
        }

        // 第二遍：组装实体，通过映射表解析父子/兄弟引用，逐条 INSERT
        List<DocumentStructureNode> entities = new ArrayList<>();
        for (DocumentIntermediateStructureNode node : nodes) {
            if (node == null || node.getNodeNo() == null) {
                continue;
            }
            DocumentStructureNode entity = DocumentStructureNode.builder()
                    .id(nodeIdMap.get(node.getNodeNo()))
                    .documentId(documentId)
                    .parseTaskId(parseTaskId)
                    .nodeNo(node.getNodeNo())
                    .nodeType(node.getNodeType())
                    .parentNodeId(resolveNodeId(nodeIdMap, node.getParentNodeNo()))
                    .prevSiblingNodeId(resolveNodeId(nodeIdMap, node.getPrevSiblingNodeNo()))
                    .nextSiblingNodeId(resolveNodeId(nodeIdMap, node.getNextSiblingNodeNo()))
                    .depth(node.getDepth())
                    .nodeCode(node.getNodeCode())
                    .title(node.getTitle())
                    .anchorText(node.getAnchorText())
                    .canonicalPath(node.getCanonicalPath())
                    .sectionPath(node.getSectionPath())
                    .contentText(node.getContentText())
                    .itemIndex(node.getSequenceNo())
                    .build();
            structureNodeMapper.insert(entity);
            entities.add(entity);
        }

        log.info("结构节点持久化完成: documentId={}, parseTaskId={}, nodeCount={}",
                documentId, parseTaskId, entities.size());
        return entities;
    }

    @Override
    public List<DocumentStructureNode> listDocumentNodes(Long documentId, Long parseTaskId) {
        if (documentId == null) {
            return List.of();
        }
        LambdaQueryWrapper<DocumentStructureNode> wrapper = new LambdaQueryWrapper<DocumentStructureNode>()
                .eq(DocumentStructureNode::getDocumentId, documentId)
                .eq(DocumentStructureNode::getIsDeleted, 0)
                .orderByAsc(DocumentStructureNode::getNodeNo);
        if (parseTaskId != null) {
            wrapper.eq(DocumentStructureNode::getParseTaskId, parseTaskId);
        }
        return structureNodeMapper.selectList(wrapper);
    }

    @Override
    public Map<Long, DocumentStructureNode> nodeMap(Long documentId, Long parseTaskId) {
        Map<Long, DocumentStructureNode> result = new LinkedHashMap<>();
        for (DocumentStructureNode node : listDocumentNodes(documentId, parseTaskId)) {
            result.put(node.getId(), node);
        }
        return result;
    }

    @Override
    public void deleteByDocumentId(Long documentId) {
        if (documentId == null) {
            return;
        }
        structureNodeMapper.delete(new LambdaQueryWrapper<DocumentStructureNode>()
                .eq(DocumentStructureNode::getDocumentId, documentId));
    }

    /**
     * 将候选节点的 Integer nodeNo 安全解析为数据库 Long ID。
     *
     * @param nodeIdMap nodeNo → 数据库 ID 映射
     * @param nodeNo    候选节点中的引用编号，可为 null
     * @return 数据库 ID，nodeNo 为 null 或映射中不存在时返回 null
     */
    private Long resolveNodeId(Map<Integer, Long> nodeIdMap, Integer nodeNo) {
        if (nodeNo == null) {
            return null;
        }
        return nodeIdMap.get(nodeNo);
    }
}

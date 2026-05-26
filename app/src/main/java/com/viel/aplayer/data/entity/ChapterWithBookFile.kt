package com.viel.aplayer.data.entity

import androidx.room.Embedded
import androidx.room.Relation

/**
 * 携带物理文件实体的章节组合数据对象。
 * 
 * 采用 Room 官方推荐的一对一关系联查（Relation）设计，
 * 在查询章节（Chapter）数据的同时，自动多表联查其物理所属的音频分轨文件（BookFile）的最新快照，
 * 从而使章节列表能即时感知其对应物理音频在虚拟文件系统（VFS）中的可达就绪状态（如 READY / MISSING）。
 * 这种设计完全避免了修改已有的底层数据表结构，做到了零数据库迁移（Migration）风险。
 */
data class ChapterWithBookFile(
    /** 嵌入的原始章节表数据实体 */
    @Embedded
    val chapter: ChapterEntity,

    /** 关联联查出的物理音频文件表实体，允许为 null（若对应的 BookFile 在数据库中被逻辑级联清除） */
    @Relation(
        parentColumn = "bookFileId",
        entityColumn = "id"
    )
    val bookFile: BookFileEntity?
)

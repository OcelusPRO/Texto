package fr.ftnl.texto.database.models.posts

import fr.ftnl.texto.database.types.BaseIntIdTable

object Tags : BaseIntIdTable("TBL_TAGS_TAG") {
    
    val name = varchar("name", 255).uniqueIndex()
    val parent = optReference("parent", Tags)
    
}
package com.viel.aplayer.ui.edit

import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
// 为每一次改动添加详尽的中文注释：导入 aspectRatio 用于维持详情页与编辑页一致的 1:1 高保真精美圆角大封面卡片比例
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Save
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.lifecycle.viewModelScope
import coil.compose.AsyncImage
import com.viel.aplayer.APlayerApplication
import com.viel.aplayer.data.entity.BookEntity
import com.viel.aplayer.ui.theme.APlayerTheme
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import java.io.File

/**
 * 为每一次改动添加详尽的中文注释：
 * 编辑书籍详细元数据的独立 Activity。
 * 通过 Intent 接收传递的书籍 ID (bookId)，采用符合现代规范的单向数据流与 Compose 精美表单设计。
 */
class EditBookActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val bookId = intent.getStringExtra(EXTRA_BOOK_ID)
        if (bookId.isNullOrBlank()) {
            Toast.makeText(this, "无效的书籍ID", Toast.LENGTH_SHORT).show()
            finish()
            return
        }

        setContent {
            APlayerTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val editViewModel: EditBookViewModel = viewModel()
                    
                    // 为每一次改动添加详尽的中文注释：在生命周期启动时自动加载指定书籍的现有元数据数据
                    LaunchedEffect(bookId) {
                        editViewModel.loadBook(bookId)
                    }

                    EditBookScreen(
                        viewModel = editViewModel,
                        onNavigationBack = { finish() },
                        onSaveSuccess = {
                            Toast.makeText(this@EditBookActivity, "保存成功", Toast.LENGTH_SHORT).show()
                            finish()
                        }
                    )
                }
            }
        }
    }

    companion object {
        const val EXTRA_BOOK_ID = "extra_book_id"
    }
}

/**
 * 为每一次改动添加详尽的中文注释：
 * 编辑书籍元数据的轻量规范化 ViewModel，生命周期依附于 EditBookActivity。
 */
class EditBookViewModel(application: Application) : AndroidViewModel(application) {
    private val repository = (application as APlayerApplication).container.libraryRepository

    private val _bookState = MutableStateFlow<BookEntity?>(null)
    val bookState = _bookState.asStateFlow()

    /**
     * 为每一次改动添加详尽的中文注释：根据书籍 ID 异步加载单本图书的底层 Room 实体记录
     */
    fun loadBook(bookId: String) {
        viewModelScope.launch {
            _bookState.value = repository.getBookById(bookId)
        }
    }

    /**
     * 为每一次改动添加详尽的中文注释：将编辑好的全新元数据异步保存并持久化回数据库。
     * @param onComplete 保存成功并持久化后的回调，一般用于关闭 Activity
     */
    fun saveBook(
        title: String,
        author: String,
        narrator: String,
        year: String,
        description: String,
        onComplete: () -> Unit
    ) {
        val currentBook = _bookState.value ?: return
        viewModelScope.launch {
            repository.updateBookDetails(
                id = currentBook.id,
                title = title.trim(),
                author = author.trim(),
                narrator = narrator.trim(),
                description = description.trim(),
                year = year.trim()
            )
            onComplete()
        }
    }
}

/**
 * 为每一次改动添加详尽的中文注释：
 * 书籍编辑屏幕的主渲染 Composable。
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun EditBookScreen(
    viewModel: EditBookViewModel,
    onNavigationBack: () -> Unit,
    onSaveSuccess: () -> Unit,
    modifier: Modifier = Modifier
) {
    val book by viewModel.bookState.collectAsStateWithLifecycle()

    Scaffold(
        modifier = modifier
            .fillMaxSize()
            .statusBarsPadding()
            .navigationBarsPadding(),
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        text = "修改书籍信息",
                        fontWeight = FontWeight.Bold,
                        style = MaterialTheme.typography.titleLarge
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onNavigationBack) {
                        Icon(
                            imageVector = Icons.AutoMirrored.Rounded.ArrowBack,
                            contentDescription = "返回"
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background,
                    titleContentColor = MaterialTheme.colorScheme.onBackground
                )
            )
        }
    ) { paddingValues ->
        val currentBook = book
        if (currentBook == null) {
            // 为每一次改动添加详尽的中文注释：加载尚未完成时，展示高颜值的居中 CircularProgressIndicator 动画
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else {
            // 为每一次改动添加详尽的中文注释：将打字输入框对应的局部状态牢牢隔离在 UI 页面内部，
            // 确保高频打字重组不串扰 ViewModel，大幅提升输入跟手感并规避卡顿
            var title by remember(currentBook) { mutableStateOf(currentBook.title) }
            var author by remember(currentBook) { mutableStateOf(currentBook.author) }
            var narrator by remember(currentBook) { mutableStateOf(currentBook.narrator) }
            var year by remember(currentBook) { mutableStateOf(currentBook.year) }
            var description by remember(currentBook) { mutableStateOf(currentBook.description) }

            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(paddingValues)
                    .imePadding() // 优雅适配键盘弹出，保证输入框不被输入法遮挡
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(20.dp)
            ) {
                // 为每一次改动添加详尽的中文注释：如果有封面路径，在顶部展示与详情页视觉完全一致的、铺满除两侧 padding 外的 1:1 高保真精美圆角大封面卡片，彰显极致的统一美感
                val coverPath = currentBook.coverPath
                if (!coverPath.isNullOrBlank()) {
                    Surface(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clip(RoundedCornerShape(24.dp)),
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shadowElevation = 4.dp
                    ) {
                        AsyncImage(
                            model = File(coverPath),
                            contentDescription = "书籍封面",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.Crop
                        )
                    }
                }

                Spacer(modifier = Modifier.height(8.dp))

                // 书名输入框
                OutlinedTextField(
                    value = title,
                    onValueChange = { title = it },
                    label = { Text("书名") },
                    placeholder = { Text("请输入书名") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // 作者输入框
                OutlinedTextField(
                    value = author,
                    onValueChange = { author = it },
                    label = { Text("作者") },
                    placeholder = { Text("请输入作者") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // 讲述人输入框
                OutlinedTextField(
                    value = narrator,
                    onValueChange = { narrator = it },
                    label = { Text("讲述人") },
                    placeholder = { Text("请输入讲述人") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // 年份输入框
                OutlinedTextField(
                    value = year,
                    onValueChange = { year = it },
                    label = { Text("年份") },
                    placeholder = { Text("请输入出版年份") },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                // 简介描述输入框
                OutlinedTextField(
                    value = description,
                    onValueChange = { description = it },
                    label = { Text("简介描述") },
                    placeholder = { Text("请输入书籍简介") },
                    minLines = 4,
                    maxLines = 8,
                    shape = RoundedCornerShape(12.dp),
                    modifier = Modifier.fillMaxWidth()
                )

                Spacer(modifier = Modifier.height(16.dp))

                // 保存修改按钮
                TextButton(
                    onClick = {
                        if (title.isBlank()) {
                            title = "Unknown" // 提供优雅的书名非空安全兜底
                        }
                        viewModel.saveBook(
                            title = title,
                            author = author,
                            narrator = narrator,
                            year = year,
                            description = description,
                            onComplete = onSaveSuccess
                        )
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(56.dp)
                        .background(
                            color = MaterialTheme.colorScheme.primary,
                            shape = RoundedCornerShape(16.dp)
                        ),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.Center
                    ) {
                        Icon(
                            imageVector = Icons.Rounded.Save,
                            contentDescription = "保存",
                            tint = MaterialTheme.colorScheme.onPrimary
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = "保存书籍信息",
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 16.sp
                        )
                    }
                }
            }
        }
    }
}

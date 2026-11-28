package com.maggie.app.ui.screens.cookbook.grocery

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import com.maggie.app.data.model.CiqualFood
import com.maggie.app.data.model.Product
import com.maggie.app.data.model.Store
import com.maggie.app.ui.UiTags
import com.maggie.app.ui.theme.MaggieTokens
import com.maggie.app.ui.theme.isMaggieDark
import com.maggie.app.ui.theme.maggieSurface
import com.maggie.app.ui.uiTagRoot

@Composable
fun ProductPickerDialog(
    products: List<Product>,
    initialQuery: String = "",
    onSelectProduct: (Product) -> Unit,
    /** null hides the « + Créer … » row: the caller needs a real product (the recurring items). */
    onSelectCustomLabel: ((String) -> Unit)?,
    onDismiss: () -> Unit,
    /** Ciqual foods offered when no product matches; null hides them (the recurring items need a real product). */
    onSearchCiqual: (suspend (String) -> List<CiqualFood>)? = null,
    onSelectCiqualFood: ((CiqualFood) -> Unit)? = null,
    /** Names each product's usual store on its row; empty omits it. */
    stores: List<Store> = emptyList(),
) {
    var searchQuery by remember { mutableStateOf(initialQuery) }
    val focusRequester = remember { FocusRequester() }

    val filteredProducts = remember(searchQuery, products) {
        filterProducts(searchQuery, products)
    }

    var ciqualFoods by remember { mutableStateOf<List<CiqualFood>>(emptyList()) }
    LaunchedEffect(searchQuery, filteredProducts.isEmpty()) {
        val query = searchQuery.trim()
        if (onSearchCiqual == null || onSelectCiqualFood == null || !filteredProducts.isEmpty() || query.length < 2) {
            ciqualFoods = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        ciqualFoods = try {
            onSearchCiqual(query)
        } catch (e: CancellationException) {
            throw e
        } catch (_: Exception) {
            emptyList()
        }
    }

    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    val coursesBlue = MaggieTokens.Module.courses.let { if (isMaggieDark()) it.second else it.first }
    val surface = maggieSurface()
    val ink = MaterialTheme.colorScheme.onSurface
    val trimmed = searchQuery.trim()

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier.fillMaxSize().uiTagRoot(),
            color = MaterialTheme.colorScheme.background,
        ) {
            Column(modifier = Modifier.fillMaxSize().statusBarsPadding()) {
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 14.dp),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    IconButton(onClick = onDismiss, modifier = Modifier.size(48.dp)) {
                        Icon(
                            Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                            contentDescription = "Fermer la recherche",
                            tint = ink,
                            modifier = Modifier.size(28.dp),
                        )
                    }
                    Row(
                        modifier = Modifier
                            .weight(1f)
                            .height(52.dp)
                            .background(surface.raised, RoundedCornerShape(14.dp))
                            .padding(horizontal = 14.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(10.dp),
                    ) {
                        Icon(Icons.Default.Search, contentDescription = null, tint = surface.textMuted, modifier = Modifier.size(18.dp))
                        BasicTextField(
                            value = searchQuery,
                            onValueChange = { searchQuery = it },
                            modifier = Modifier
                                .weight(1f)
                                .focusRequester(focusRequester)
                                .testTag(UiTags.PRODUCT_SEARCH),
                            singleLine = true,
                            textStyle = TextStyle(color = ink, fontSize = 17.sp),
                            cursorBrush = SolidColor(coursesBlue),
                        )
                    }
                }

                LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(horizontal = 16.dp)) {
                    items(filteredProducts) { product ->
                        PickerRow(product.name, productSubtitle(product, stores)) { onSelectProduct(product) }
                    }
                    items(ciqualFoods) { food ->
                        PickerRow(food.alimNameFr, "Ingr\u00e9dient Ciqual") { onSelectCiqualFood?.invoke(food) }
                    }
                    if (onSelectCustomLabel != null && trimmed.isNotEmpty()) {
                        item {
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(56.dp)
                                    .clickable { onSelectCustomLabel(trimmed) }
                                    .padding(horizontal = 4.dp),
                                contentAlignment = Alignment.CenterStart,
                            ) {
                                Text(text = "+ Cr\u00e9er \u00ab $trimmed \u00bb", fontSize = 16.sp, color = coursesBlue)
                            }
                        }
                    }
                }
            }
        }
    }
}

/** A search row: 60 dp, the name over a muted line, a hairline underneath. */
@Composable
private fun PickerRow(title: String, subtitle: String, onClick: () -> Unit) {
    val surface = maggieSurface()
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .heightIn(min = 60.dp)
            .padding(horizontal = 4.dp),
        verticalArrangement = Arrangement.Center,
    ) {
        Column(modifier = Modifier.heightIn(min = 59.dp), verticalArrangement = Arrangement.Center) {
            Text(text = title, fontSize = 16.sp, color = MaterialTheme.colorScheme.onSurface)
            Text(text = subtitle, fontSize = 13.sp, color = surface.textMuted)
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(surface.text.copy(alpha = if (isMaggieDark()) MaggieTokens.Divider.DARK else MaggieTokens.Divider.LIGHT)))
    }
}

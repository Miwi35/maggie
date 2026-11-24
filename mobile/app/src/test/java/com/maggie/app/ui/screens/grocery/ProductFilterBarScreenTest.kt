package com.maggie.app.ui.screens.grocery

import androidx.compose.ui.test.getUnclippedBoundsInRoot
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.height
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.maggie.app.data.model.ProductQuery
import com.maggie.app.data.model.Store
import com.maggie.app.screentest.ScreenRule
import com.maggie.app.ui.UiTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.onNodeWithText
import com.maggie.app.data.model.ProductSort
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The filters of Courses › Produits (MAG-606) fit a phone's width: none is cut by the right edge (MAG-675).
 * On the reporting phone, 1080 × 2340 px at xxhdpi, that is 360 dp. Native graphics: the legacy mode
 * measures one pixel per character, so no chip would ever reach the edge.
 */
@RunWith(AndroidJUnit4::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
class ProductFilterBarScreenTest {

    @get:Rule
    val compose = ScreenRule()

    private fun assertDrawnWhole(state: ProductUiState) {
        compose.setContent { ProductFilterBar(state = state, onStore = {}, onAisle = {}, onSort = {}) }

        val screen = compose.onRoot().getUnclippedBoundsInRoot()
        for (tag in listOf(UiTags.PRODUCT_FILTER_STORE, UiTags.PRODUCT_FILTER_AISLE, UiTags.PRODUCT_FILTER_SORT)) {
            val chip = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
            assertTrue("$tag ends ${screen.right - chip.right} from the right edge", screen.right - chip.right >= 16.dp)
        }
    }

    @Test
    fun `the three filters are drawn whole inside the screen's margins`() {
        assertDrawnWhole(ProductUiState())
    }

    @Test
    fun `a chosen store lengthens its chip and the three filters are still drawn whole`() {
        val store = Store(id = "s1", name = "Super U")
        assertDrawnWhole(ProductUiState(stores = listOf(store), query = ProductQuery(storeId = store.id)))
    }

    @Test
    fun `the three filters sit on one line, 36 dp high`() {
        compose.setContent { ProductFilterBar(state = ProductUiState(), onStore = {}, onAisle = {}, onSort = {}) }

        val tops = listOf(UiTags.PRODUCT_FILTER_STORE, UiTags.PRODUCT_FILTER_AISLE, UiTags.PRODUCT_FILTER_SORT)
            .map { tag ->
                val box = compose.onNodeWithTag(tag).getUnclippedBoundsInRoot()
                assertTrue("$tag is ${box.height} high", box.height >= 36.dp)
                box.top
            }
        assertEquals("the filters share one line", 1, tops.toSet().size)
    }

    @Test
    fun `the sort pill is never shown as chosen`() {
        compose.setContent {
            ProductFilterBar(state = ProductUiState(query = ProductQuery(sort = ProductSort.AISLE, storeId = "s1")), onStore = {}, onAisle = {}, onSort = {})
        }

        compose.onNodeWithTag(UiTags.PRODUCT_FILTER_SORT).assertIsEnabled()
        compose.onNodeWithText("Trier : ${ProductSort.AISLE.label}").assertExists()
    }
}

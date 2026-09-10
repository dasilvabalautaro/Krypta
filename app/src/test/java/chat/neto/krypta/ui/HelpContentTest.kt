package chat.neto.krypta.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El FAQ de Ayuda es datos puros ([HelpContent]); estas comprobaciones evitan que se cuele
 * una entrada vacía, duplicada o desordenada (la UI de [HelpScreen] confía en su forma).
 */
class HelpContentTest {

    @Test
    fun `there are help items`() {
        assertTrue(HelpContent.items.isNotEmpty())
    }

    @Test
    fun `no field is blank`() {
        HelpContent.items.forEach { item ->
            assertTrue("categoría vacía", item.category.isNotBlank())
            assertTrue("pregunta vacía", item.question.isNotBlank())
            assertTrue("respuesta vacía", item.answer.isNotBlank())
        }
    }

    @Test
    fun `questions are unique`() {
        val questions = HelpContent.items.map { it.question }
        assertEquals(questions.size, questions.distinct().size)
    }

    @Test
    fun `answers are concise but not stubs`() {
        HelpContent.items.forEach { item ->
            // Ni una respuesta de una palabra ni un muro de texto: la ayuda in-app es un resumen.
            // El mensaje dice la longitud real: decía solo "demasiado corta" y mandaba a
            // buscar en la dirección contraria cuando lo que sobraba era texto.
            assertTrue(
                "respuesta de ${item.answer.length} caracteres (se esperan 40..600): ${item.question}",
                item.answer.length in 40..600,
            )
        }
    }

    @Test
    fun `categoriesInOrder covers every item without duplicates and preserves order`() {
        val cats = HelpContent.categoriesInOrder
        assertEquals(cats.size, cats.distinct().size)
        // Toda categoría usada por un item está listada, y viceversa.
        assertEquals(HelpContent.items.map { it.category }.distinct(), cats)
        // Agrupar por categorías en ese orden reordena sin perder ni añadir items.
        val grouped = cats.flatMap { c -> HelpContent.items.filter { it.category == c } }
        assertEquals(HelpContent.items.toSet(), grouped.toSet())
        assertEquals(HelpContent.items.size, grouped.size)
    }
}

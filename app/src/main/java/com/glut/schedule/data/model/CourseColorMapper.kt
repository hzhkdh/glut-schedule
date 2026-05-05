package com.glut.schedule.data.model

import kotlin.math.pow
import kotlin.math.sqrt

object CourseColorMapper {
    val palette: List<String> = listOf(
        "#3B82F6", // blue
        "#D97706", // amber
        "#8B5CF6", // violet
        "#0D9488", // teal
        "#E11D48", // rose
        "#DB2777", // pink
        "#4F46E5", // indigo
        "#92400E", // brown
        "#0891B2", // cyan
        "#EA580C", // orange
        "#16A34A", // green
        "#BE185D" // raspberry
    )

    fun colorForCourse(courseId: String, title: String): String {
        val key = colorKey(courseId = courseId, title = title)
        return palette[paletteIndexForKey(key)]
    }

    fun assignColors(courses: List<ScheduleCourse>): List<ScheduleCourse> {
        val assignedIndexes = mutableMapOf<String, Int>()
        val occupiedIndexes = mutableSetOf<Int>()

        courses.forEach { course ->
            val key = colorKey(course.id, course.title)
            if (assignedIndexes.containsKey(key)) return@forEach

            val baseIndex = paletteIndexForKey(key)
            val index = findAvailableIndex(startIndex = baseIndex, occupiedIndexes = occupiedIndexes)
            assignedIndexes[key] = index
            occupiedIndexes += index
        }

        avoidCloseAdjacentColors(courses, assignedIndexes, occupiedIndexes)

        return courses.map { course ->
            val key = colorKey(course.id, course.title)
            course.copy(colorHex = palette[assignedIndexes.getValue(key)])
        }
    }

    private fun avoidCloseAdjacentColors(
        courses: List<ScheduleCourse>,
        assignedIndexes: MutableMap<String, Int>,
        occupiedIndexes: MutableSet<Int>
    ) {
        courses.forEachIndexed { leftIndex, left ->
            courses.drop(leftIndex + 1).forEach { right ->
                val leftKey = colorKey(left.id, left.title)
                val rightKey = colorKey(right.id, right.title)
                if (leftKey == rightKey) return@forEach
                if (!areVisuallyAdjacent(left, right)) return@forEach

                val leftColor = palette[assignedIndexes.getValue(leftKey)]
                val rightColor = palette[assignedIndexes.getValue(rightKey)]
                if (!areColorsClose(leftColor, rightColor)) return@forEach

                val nextIndex = findAvailableIndex(
                    startIndex = assignedIndexes.getValue(rightKey) + 2,
                    occupiedIndexes = occupiedIndexes
                )
                occupiedIndexes -= assignedIndexes.getValue(rightKey)
                assignedIndexes[rightKey] = nextIndex
                occupiedIndexes += nextIndex
            }
        }
    }

    private fun findAvailableIndex(startIndex: Int, occupiedIndexes: Set<Int>): Int {
        if (occupiedIndexes.size >= palette.size) return startIndex.floorMod(palette.size)

        var index = startIndex.floorMod(palette.size)
        repeat(palette.size) {
            if (index !in occupiedIndexes) return index
            index = (index + 5).floorMod(palette.size)
        }
        return startIndex.floorMod(palette.size)
    }

    private fun areVisuallyAdjacent(left: ScheduleCourse, right: ScheduleCourse): Boolean {
        return left.occurrences.any { leftOccurrence ->
            right.occurrences.any { rightOccurrence ->
                leftOccurrence.dayOfWeek == rightOccurrence.dayOfWeek &&
                    leftOccurrence.endSection + 1 >= rightOccurrence.startSection &&
                    rightOccurrence.endSection + 1 >= leftOccurrence.startSection
            }
        }
    }

    private fun areColorsClose(leftHex: String, rightHex: String): Boolean {
        val left = rgb(leftHex)
        val right = rgb(rightHex)
        val distance = sqrt(
            (left.red - right.red).toDouble().pow(2) +
                (left.green - right.green).toDouble().pow(2) +
                (left.blue - right.blue).toDouble().pow(2)
        )
        return distance < 95.0
    }

    private fun paletteIndexForKey(key: String): Int {
        return stableHash(key).floorMod(palette.size)
    }

    private fun colorKey(courseId: String, title: String): String {
        return title.trim().lowercase().ifBlank { courseId.trim().lowercase() }
    }

    private fun stableHash(value: String): Int {
        var hash = 0x811C9DC5.toInt()
        value.forEach { char ->
            hash = hash xor char.code
            hash *= 0x01000193
        }
        return hash
    }

    private fun rgb(hex: String): Rgb {
        val clean = hex.removePrefix("#")
        return Rgb(
            red = clean.substring(0, 2).toInt(16),
            green = clean.substring(2, 4).toInt(16),
            blue = clean.substring(4, 6).toInt(16)
        )
    }

    private fun Int.floorMod(other: Int): Int = Math.floorMod(this, other)

    private data class Rgb(
        val red: Int,
        val green: Int,
        val blue: Int
    )
}

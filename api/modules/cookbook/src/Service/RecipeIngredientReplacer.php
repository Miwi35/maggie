<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Service;

use Maggie\Cookbook\Entity\Ingredient;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Entity\RecipeIngredient;
use Maggie\Cookbook\Entity\RecipeStep;
use Maggie\Cookbook\Exception\InvalidIngredientReplacementException;
use Maggie\Grocery\Enum\Unit;

/**
 * Swaps the product of one line of a recipe and carries what points at the old
 * one over to the new one (MAG-601): the steps' links to the ingredient. The
 * texts of the steps are only rewritten when the caller asks for it — no text
 * is ever changed on a deduction. Nothing is flushed.
 *
 * @phpstan-type TextSuggestion array{stepId: string, position: int, before: string, after: string}
 */
class RecipeIngredientReplacer
{
    /**
     * @param Ingredient $replacement the product that takes the line — the old one, to change the quantity only
     *
     * @return list<TextSuggestion> the steps whose text cites the old name or quantity, with what it would become; applied when `$rewriteText`
     *
     * @throws InvalidIngredientReplacementException
     */
    public function replace(Recipe $recipe, string $ingredientId, Ingredient $replacement, ?float $quantity, ?Unit $unit, bool $rewriteText): array
    {
        $line = $this->lineOf($recipe, $ingredientId)
            ?? throw new InvalidIngredientReplacementException(\sprintf('The recipe has no ingredient "%s" to replace.', $ingredientId));
        $old = $line->getIngredient();

        if ($replacement->getUser()->getId()->toRfc4122() !== $recipe->getUser()->getId()->toRfc4122()) {
            throw new InvalidIngredientReplacementException('The new ingredient is not in your catalogue.');
        }
        $sameProduct = $old->getId()->toRfc4122() === $replacement->getId()->toRfc4122();
        if (!$sameProduct && null !== $this->lineOf($recipe, (string) $replacement->getId())) {
            throw new InvalidIngredientReplacementException(\sprintf('"%s" is already one of the recipe\'s ingredients. Change its quantity instead.', $replacement->getName()));
        }

        $oldQuantity = $line->getQuantity();
        $oldUnit = $line->getUnit();
        $newQuantity = $quantity ?? $oldQuantity;
        $newUnit = $unit ?? $oldUnit;
        if ($newQuantity <= 0) {
            throw new InvalidIngredientReplacementException('The quantity must be greater than 0.');
        }

        $line->setIngredient($replacement)->setQuantity($newQuantity)->setUnit($newUnit);

        $suggestions = [];
        foreach ($recipe->getSteps() as $step) {
            $linked = $this->linksTo($step, $old);
            if ($linked && !$sameProduct) {
                $step->setIngredients(array_map(static fn (Ingredient $i) => $i === $old ? $replacement : $i, $step->linkedIngredients()));
            }

            $text = $step->getText();
            $after = $sameProduct ? $text : (StepTextCitations::renameIngredient($text, $old->getName(), $replacement->getName()) ?? $text);
            if ($linked) {
                $after = StepTextCitations::requantify($after, $oldQuantity, $oldUnit, $newQuantity, $newUnit) ?? $after;
            }
            if ($after === $text) {
                continue;
            }

            $suggestions[] = ['stepId' => (string) $step->getId(), 'position' => $step->getPosition(), 'before' => $text, 'after' => $after];
            if ($rewriteText) {
                $step->setText($after);
            }
        }

        return $suggestions;
    }

    private function lineOf(Recipe $recipe, string $ingredientId): ?RecipeIngredient
    {
        foreach ($recipe->getIngredients() as $line) {
            if (0 === strcasecmp((string) $line->getIngredient()->getId(), $ingredientId)) {
                return $line;
            }
        }

        return null;
    }

    private function linksTo(RecipeStep $step, Ingredient $ingredient): bool
    {
        foreach ($step->linkedIngredients() as $linked) {
            if ($linked === $ingredient) {
                return true;
            }
        }

        return false;
    }
}

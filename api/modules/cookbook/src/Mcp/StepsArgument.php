<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Mcp;

use Maggie\Cookbook\State\RecipeStepsPayload;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;

/**
 * The `steps` of the recipe tools (MAG-490): a JSON array, like `ingredients`,
 * read by the same rules as the API body.
 *
 * @phpstan-import-type StepInput from RecipeStepsPayload
 */
final class StepsArgument
{
    public const DESCRIPTION = 'The method goes in steps, notes keep the tips. Steps is a JSON array of the steps, in order (the first is step 1), each {text, durationMinutes?, background?, ingredientIds?, utensils?, waitsFor?}: ingredientIds names ingredients of the recipe by ingredientId or by name (only a link: a partial quantity goes in the text); utensils are utensil ids or names, added to the recipe\'s utensils; background (needs a duration) means the timer runs while the cook moves on; waitsFor lists the positions of earlier steps that must be finished first.';

    /**
     * @return list<StepInput>|null
     *
     * @throws BadRequestHttpException the argument is not a JSON array of steps
     */
    public static function parse(?string $steps): ?array
    {
        if (null === $steps) {
            return null;
        }

        try {
            $list = json_decode($steps, true, 512, JSON_THROW_ON_ERROR);
        } catch (\JsonException) {
            throw new BadRequestHttpException('steps must be a JSON array of steps.');
        }

        return RecipeStepsPayload::fromList($list);
    }
}

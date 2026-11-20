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

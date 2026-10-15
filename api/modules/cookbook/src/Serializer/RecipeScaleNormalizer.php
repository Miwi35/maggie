<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Serializer;

use ApiPlatform\Metadata\Get;
use Maggie\Cookbook\Entity\Recipe;
use Maggie\Cookbook\Service\RecipeScaler;
use Maggie\Grocery\Enum\Unit;
use Symfony\Component\HttpFoundation\RequestStack;
use Symfony\Component\HttpKernel\Exception\BadRequestHttpException;
use Symfony\Component\Serializer\Normalizer\NormalizerAwareInterface;
use Symfony\Component\Serializer\Normalizer\NormalizerAwareTrait;
use Symfony\Component\Serializer\Normalizer\NormalizerInterface;

/**
 * The recipe sheet's simulation (MAG-482): `GET /api/recipes/{id}?guests=N`
 * answers the recipe with its quantities scaled to N guests, by the rule the
 * grocery list applies ({@see RecipeScaler}). Nothing is stored: only the
 * response changes, and it says so with `scaledFor` and `scaleFactor`.
 *
 * Only on the item read, and only on the recipe asked for — never on a
 * collection, nor on a recipe nested in another resource.
 */
final class RecipeScaleNormalizer implements NormalizerInterface, NormalizerAwareInterface
{
    use NormalizerAwareTrait;

    private const ALREADY_CALLED = 'recipe_scale_normalizer_already_called';

    public function __construct(
        private readonly RequestStack $requestStack,
    ) {
    }

    public function normalize(mixed $data, ?string $format = null, array $context = []): array|string|int|float|bool|\ArrayObject|null
    {
        \assert($data instanceof Recipe);

        $normalized = $this->normalizer->normalize($data, $format, $context + [self::ALREADY_CALLED => true]);
        $guests = $this->guestsAsked($data, $context);

        if (null === $guests || !\is_array($normalized)) {
            return $normalized;
        }

        $factor = RecipeScaler::factor($data, $guests);

        foreach ($normalized['ingredients'] ?? [] as $i => $line) {
            $unit = \is_array($line) && \is_string($line['unit'] ?? null) ? Unit::tryFrom($line['unit']) : null;
            if (null === $unit || !is_numeric($line['quantity'] ?? null)) {
                continue;
            }

            $normalized['ingredients'][$i]['quantity'] = RecipeScaler::scale((float) $line['quantity'], $unit, $factor);
        }

        $normalized['scaledFor'] = $guests;
        $normalized['scaleFactor'] = round($factor, 4);

        return $normalized;
    }

    public function supportsNormalization(mixed $data, ?string $format = null, array $context = []): bool
    {
        return $data instanceof Recipe && !isset($context[self::ALREADY_CALLED]);
    }

    /** @return array<class-string, bool> */
    public function getSupportedTypes(?string $format): array
    {
        return [Recipe::class => false];
    }

    /**
     * The guests the request asks the recipe for, null when it asks for none.
     *
     * @param array<string, mixed> $context
     *
     * @throws BadRequestHttpException when `guests` is not a whole number of at least 1
     */
    private function guestsAsked(Recipe $recipe, array $context): ?int
    {
        $request = $this->requestStack->getMainRequest();

        if (null === $request
            || !($context['operation'] ?? null) instanceof Get
            || (string) $recipe->getId() !== (string) $request->attributes->get('id')
            || !$request->query->has('guests')) {
            return null;
        }

        $guests = $request->query->all()['guests'] ?? null;

        if (!\is_string($guests) || 1 !== preg_match('/^[1-9]\d{0,2}$/', $guests)) {
            throw new BadRequestHttpException('guests must be a whole number of at least 1.');
        }

        return (int) $guests;
    }
}

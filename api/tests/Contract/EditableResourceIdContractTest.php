<?php

declare(strict_types=1);

namespace App\Tests\Contract;

use ApiPlatform\Metadata\ApiProperty;
use ApiPlatform\Metadata\Patch;
use ApiPlatform\Metadata\Property\Factory\PropertyMetadataFactoryInterface;
use ApiPlatform\Metadata\Property\Factory\PropertyNameCollectionFactoryInterface;
use ApiPlatform\Metadata\Put;
use ApiPlatform\Metadata\Resource\Factory\ResourceMetadataCollectionFactoryInterface;
use ApiPlatform\Metadata\Resource\Factory\ResourceNameCollectionFactoryInterface;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;

/**
 * MAG-471: the admin sends the whole record back on a save, `id` included and
 * spelled as an IRI (`/api/accounts/<ulid>`). When the `id` is writable the
 * serializer parses that IRI as a Ulid and the save answers 400 — MAG-49
 * (envelopes), MAG-353 (categories) and MAG-480 (accounts, loans, ingredients,
 * agendas, events, tasks) each fixed one resource by hand, after a recette.
 *
 * The guard reads the resource metadata, so it also covers an operation
 * declared in a config file or added by a bundle, and a child class that
 * cannot see its parent's private property.
 */
final class EditableResourceIdContractTest extends KernelTestCase
{
    public function testEveryResourceWithAPatchOrPutHasAReadOnlyIdentifier(): void
    {
        $container = self::getContainer();

        $names = $container->get(ResourceNameCollectionFactoryInterface::class);
        self::assertInstanceOf(ResourceNameCollectionFactoryInterface::class, $names);

        $resources = $container->get(ResourceMetadataCollectionFactoryInterface::class);
        self::assertInstanceOf(ResourceMetadataCollectionFactoryInterface::class, $resources);

        $propertyNames = $container->get(PropertyNameCollectionFactoryInterface::class);
        self::assertInstanceOf(PropertyNameCollectionFactoryInterface::class, $propertyNames);

        $properties = $container->get(PropertyMetadataFactoryInterface::class);
        self::assertInstanceOf(PropertyMetadataFactoryInterface::class, $properties);

        $checked = 0;
        $offenders = [];

        foreach ($names->create() as $resourceClass) {
            if (!$this->declaresAWriteOperation($resources, $resourceClass)) {
                continue;
            }

            foreach ($propertyNames->create($resourceClass) as $propertyName) {
                $property = $properties->create($resourceClass, $propertyName);

                if (!$property->isIdentifier()) {
                    continue;
                }

                ++$checked;

                if (!$this->isDeclaredReadOnly($resourceClass, $propertyName)) {
                    $offenders[] = sprintf('%s::$%s', $resourceClass, $propertyName);
                }
            }
        }

        self::assertGreaterThan(0, $checked, 'No editable resource with an identifier was found: the guard no longer checks anything.');
        self::assertSame(
            [],
            $offenders,
            sprintf(
                "These resources declare a Patch or Put and a writable identifier:\n%s\nThe admin sends the record back with its IRI as `id`; a writable `id` is parsed as a Ulid and every save answers 400. Add #[%s(writable: false)] on the `id` property (protected, when a child resource must inherit it).",
                implode("\n", $offenders),
                (new \ReflectionClass(ApiProperty::class))->getShortName(),
            ),
        );
    }

    /**
     * The attribute itself, not the resource metadata: the metadata reports a
     * setter-less `id` as read-only whether or not the attribute is there, yet
     * the denormalizer still parses the IRI (MAG-480). Reflection also fails
     * a child resource whose parent keeps `id` private, which the attribute
     * on the parent cannot reach.
     */
    private function isDeclaredReadOnly(string $resourceClass, string $propertyName): bool
    {
        if (!property_exists($resourceClass, $propertyName)) {
            return false;
        }

        foreach ((new \ReflectionProperty($resourceClass, $propertyName))->getAttributes(ApiProperty::class) as $attribute) {
            if (false === ($attribute->getArguments()['writable'] ?? null)) {
                return true;
            }
        }

        return false;
    }

    private function declaresAWriteOperation(ResourceMetadataCollectionFactoryInterface $resources, string $resourceClass): bool
    {
        foreach ($resources->create($resourceClass) as $resource) {
            foreach ($resource->getOperations() ?? [] as $operation) {
                if ($operation instanceof Patch || $operation instanceof Put) {
                    return true;
                }
            }
        }

        return false;
    }
}

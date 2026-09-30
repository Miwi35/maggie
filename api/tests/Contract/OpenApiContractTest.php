<?php

declare(strict_types=1);

namespace App\Tests\Contract;

use ApiPlatform\OpenApi\Factory\OpenApiFactoryInterface;
use Symfony\Bundle\FrameworkBundle\Test\KernelTestCase;
use Symfony\Component\Serializer\Normalizer\NormalizerInterface;

/**
 * The OpenAPI document API Platform derives from the entities, frozen into
 * contract/openapi.json.
 *
 * The admin is generated from this document at runtime (API Platform Admin
 * reads the Hydra/OpenAPI description), and the mobile DTOs are hand-written
 * against it. So a property renamed on an entity, an operation dropped, a type
 * widened — each is a client-visible change that no PHP test would otherwise
 * notice. Freezing the document does not forbid any of that; it forces the
 * change to appear in the diff of the pull request that makes it.
 *
 * The document is stored whole rather than reduced to "the interesting parts",
 * because which part is interesting is exactly what nobody can predict: the
 * regressions this ticket comes from were a filter that was declared but
 * inert, and a relation serialised as an IRI where an id was expected.
 */
final class OpenApiContractTest extends KernelTestCase
{
    use ContractSnapshotTrait;

    public function testTheOpenApiDocumentMatchesTheContract(): void
    {
        $this->assertMatchesContract(
            'openapi.json',
            $this->openApiDocument(),
            'Something changed in the HTTP surface API Platform exposes: an operation, a path, a parameter or a schema. Check that the admin and the mobile DTOs still hold before regenerating.',
        );
    }

    /**
     * The clients address resources by path. A path that disappears is the
     * most brutal contract break there is, and the assertion above reports it
     * as one line among thousands — this one names it.
     */
    public function testEveryResourceStillExposesItsCollectionPath(): void
    {
        $paths = array_keys($this->openApiDocument()['paths'] ?? []);

        foreach (['/api/tasks', '/api/events', '/api/agendas', '/api/notifications', '/api/recipes', '/api/grocery_lists'] as $path) {
            self::assertContains($path, $paths, sprintf('The collection path "%s" is no longer exposed.', $path));
        }
    }

    /**
     * @return array<string, mixed>
     */
    private function openApiDocument(): array
    {
        $container = self::getContainer();

        $factory = $container->get(OpenApiFactoryInterface::class);
        self::assertInstanceOf(OpenApiFactoryInterface::class, $factory);

        $normalizer = $container->get('serializer');
        self::assertInstanceOf(NormalizerInterface::class, $normalizer);

        $document = $normalizer->normalize($factory(), 'json');
        self::assertIsArray($document);

        // `servers` carries whatever host the document was generated against,
        // which is the CI runner here and a laptop there. Dropping it keeps
        // the contract about the API's shape instead of where it happened to
        // be built — the same mistake as b333376, which published a Mercure
        // topic containing an absolute, host-dependent IRI.
        unset($document['servers']);

        /* @var array<string, mixed> $document */
        return $document;
    }
}

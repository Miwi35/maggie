<?php

declare(strict_types=1);

namespace Maggie\Cookbook\Entity;

use ApiPlatform\Doctrine\Orm\Filter\OrderFilter;
use ApiPlatform\Metadata\ApiFilter;
use ApiPlatform\Metadata\ApiProperty;
use ApiPlatform\Metadata\ApiResource;
use ApiPlatform\Metadata\Delete;
use ApiPlatform\Metadata\Get;
use ApiPlatform\Metadata\GetCollection;
use ApiPlatform\Metadata\Patch;
use ApiPlatform\Metadata\Post;
use Doctrine\ORM\Mapping as ORM;
use Maggie\Cookbook\Repository\UtensilRepository;
use Maggie\Cookbook\State\CreateUtensilProcessor;
use Maggie\Cookbook\State\DeleteUtensilProcessor;
use Maggie\Cookbook\State\UpdateUtensilProcessor;
use Maggie\Core\Contract\IndexableInterface;
use Maggie\Core\Contract\MercurePublishable;
use Maggie\Core\Contract\OwnedByUserInterface;
use Maggie\Core\Elasticsearch\Attribute\Indexed;
use Maggie\Core\Elasticsearch\Attribute\IndexedField;
use Maggie\Core\Elasticsearch\Attribute\IndexedRelation;
use Maggie\Core\Elasticsearch\State\ElasticsearchCollectionProvider;
use Maggie\Core\Elasticsearch\State\ElasticsearchItemProvider;
use Maggie\Core\Entity\User;
use Maggie\Core\Mercure\Trait\MercurePayloadFilterTrait;
use Symfony\Component\Uid\Ulid;
use Symfony\Component\Validator\Constraints as Assert;

/**
 * What a recipe is made with (oven, Thermomix, mandoline…), in a catalogue of
 * the user's own that one recipe after another reuses (MAG-489).
 *
 * A name is unique per user, whatever its case and the spaces around it:
 * creating « thermomix » once « Thermomix » exists answers the existing one.
 */
#[ORM\Entity(repositoryClass: UtensilRepository::class)]
#[ApiFilter(OrderFilter::class, properties: ['name'])]
// Out of the global search: a pan is not what « search everything » looks for.
#[Indexed(index: 'utensils', module: 'cookbook', globalSearch: false)]
#[ApiResource(operations: [
    new GetCollection(provider: ElasticsearchCollectionProvider::class),
    new Get(provider: ElasticsearchItemProvider::class),
    new Post(processor: CreateUtensilProcessor::class),
    new Patch(processor: UpdateUtensilProcessor::class),
    new Delete(processor: DeleteUtensilProcessor::class),
])]
class Utensil implements MercurePublishable, OwnedByUserInterface, IndexableInterface
{
    use MercurePayloadFilterTrait;

    // The admin sends a record back with `id` set to its IRI; the id is never
    // writable, so it must not be parsed as a Ulid on the way in.
    #[ORM\Id]
    #[ORM\Column(type: 'ulid')]
    #[ApiProperty(writable: false)]
    private Ulid $id;

    #[ORM\Column(length: 255)]
    #[Assert\NotBlank(normalizer: 'trim')]
    #[Assert\Length(max: 255)]
    // keyword: the catalogue is sorted by name, and Elasticsearch cannot sort
    // on an analysed text field — only on its keyword sub-field.
    #[IndexedField(type: 'text', boost: 3.0, keyword: true)]
    private string $name;

    #[ORM\ManyToOne(targetEntity: User::class)]
    #[ORM\JoinColumn(nullable: false)]
    #[IndexedRelation(targetEntity: User::class, sourceField: 'userId')]
    private User $user;

    public function __construct()
    {
        $this->id = new Ulid();
    }

    /** The name as it is stored and compared: without the spaces around it. */
    public static function normalizeName(string $name): string
    {
        return trim($name);
    }

    public function getId(): Ulid
    {
        return $this->id;
    }

    public function getName(): string
    {
        return $this->name;
    }

    public function setName(string $name): static
    {
        $this->name = self::normalizeName($name);

        return $this;
    }

    public function getUser(): User
    {
        return $this->user;
    }

    public function setUser(User $user): static
    {
        $this->user = $user;

        return $this;
    }

    /** @return array<string, mixed> */
    public function toSearchDocument(): array
    {
        return [
            'name' => $this->name,
            'userId' => (string) $this->user->getId(),
        ];
    }

    /** @return array<string, mixed> */
    public function toMercurePayload(?array $changedProperties = null): array
    {
        return self::filterPayload([
            'name' => $this->name,
        ], $changedProperties);
    }

    /**
     * The utensil as a recipe serves it, so a client can send it back as is.
     *
     * @return array{'@id': string, id: string, name: string}
     */
    public function toRecipePayload(): array
    {
        return [
            '@id' => '/api/utensils/'.$this->id,
            'id' => (string) $this->id,
            'name' => $this->name,
        ];
    }
}

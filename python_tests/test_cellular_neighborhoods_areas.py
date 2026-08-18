"""Cellular-neighborhood windows are built within a unit, never across one.

The unit is an independent area when areas are configured, otherwise an image.
A cell in the next TMA core is not a neighbour, so counting it would put a
mixture into the composition vector that the tissue does not contain.

This loop used to live loose in the script body, where the AST-based test
loader could not reach it; it was verified only by running the whole script
end-to-end. It is now a top-level function with its collaborators injected,
so the partitioning itself can be asserted.
"""

import numpy as np

from conftest import load_script_symbol

SCRIPT = "cellular_neighborhoods.py"


def _builder():
    return load_script_symbol(SCRIPT, "build_composition_per_unit", {"np": np})


class _RecordingBlock:
    """Stands in for _composition_for_block, recording what each call saw."""

    def __init__(self, n_classes=2):
        self.calls = []
        self.n_classes = n_classes

    def __call__(self, unit, block_coords, block_labels, radius_px=None):
        self.calls.append(
            {
                "unit": unit,
                "n": len(block_coords),
                "coords": np.array(block_coords),
                "radius_px": radius_px,
            }
        )
        # One row per cell; encode the unit so the caller can prove placement.
        return np.full((len(block_coords), self.n_classes), float(unit))


def test_each_unit_sees_only_its_own_cells():
    build = _builder()
    coords = np.array([[0.0, 0.0], [1.0, 0.0], [900.0, 0.0], [901.0, 0.0]])
    labels = np.array([0, 1, 0, 1])
    units = np.array([0, 0, 1, 1])
    composition = np.zeros((4, 2))
    block = _RecordingBlock()

    build(composition, coords, labels, units, np.unique(units), None, block)

    assert [c["n"] for c in block.calls] == [2, 2]
    # Unit 1's block must contain only the far-away pair.
    far = next(c for c in block.calls if c["unit"] == 1)
    assert far["coords"][:, 0].min() >= 900.0   # x column, not both axes


def test_rows_land_on_the_right_cells_when_units_are_interleaved():
    """The failure this guards: writing a block's rows to the wrong slice.
    With units interleaved, a naive contiguous write silently shifts cells."""
    build = _builder()
    coords = np.array([[0.0, 0.0], [900.0, 0.0], [1.0, 0.0], [901.0, 0.0]])
    labels = np.array([0, 0, 1, 1])
    units = np.array([0, 1, 0, 1])
    composition = np.zeros((4, 2))

    build(composition, coords, labels, units, np.unique(units), None, _RecordingBlock())

    # _RecordingBlock writes the unit id into every column.
    assert composition[:, 0].tolist() == [0.0, 1.0, 0.0, 1.0]


def test_pixel_size_is_looked_up_through_the_units_image_not_its_id():
    # Area ids and image indices are different numbering schemes. Indexing the
    # pixel-size table with an AREA id would read another image's calibration.
    build = _builder()
    coords = np.zeros((4, 2))
    labels = np.zeros(4, dtype=int)
    units = np.array([5, 5, 9, 9])  # sparse, non-zero-based area ids
    image_ids = np.array([0, 0, 1, 1])
    seen = []

    build(
        np.zeros((4, 2)),
        coords,
        labels,
        units,
        np.unique(units),
        image_ids,
        _RecordingBlock(),
        radius_px_fn=lambda img: (seen.append(img) or 10.0),
    )

    assert seen == [0, 1]


def test_an_empty_unit_is_skipped_rather_than_calling_the_block_builder():
    build = _builder()
    coords = np.zeros((2, 2))
    labels = np.zeros(2, dtype=int)
    units = np.array([0, 0])
    block = _RecordingBlock()

    processed = build(
        np.zeros((2, 2)), coords, labels, units, np.array([0, 7]), None, block
    )

    assert [c["unit"] for c in block.calls] == [0]
    assert processed == [(0, 2)]


def test_every_cell_is_written_exactly_once():
    build = _builder()
    n = 30
    rng = np.random.RandomState(0)
    units = rng.randint(0, 4, size=n)
    composition = np.full((n, 2), np.nan)

    build(
        composition,
        rng.rand(n, 2),
        rng.randint(0, 2, size=n),
        units,
        np.unique(units),
        None,
        _RecordingBlock(),
    )

    # A NaN left behind means a cell was never assigned a window.
    assert not np.isnan(composition).any()

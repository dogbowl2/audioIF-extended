"""Exports Inworld's SAVA model as a one-frame streaming step in ONNX.

The app runs the exported step with ONNX Runtime on the phone to tell when the
player has finished a command (see SavaTurnDetector.java). Each call takes one
40 ms frame (640 samples of 16 kHz mono audio) and the streaming state, and
returns the speech and end-of-turn probabilities plus the next state.

tools/sava/fetch-sava.ps1 sets up the environment, runs this script and copies
the int8 model into the app's assets. To run it by hand:

    pip install torch --index-url https://download.pytorch.org/whl/cpu
    pip install --no-deps inworld-sava
    pip install huggingface-hub safetensors soundfile numpy onnx onnxruntime onnxscript
    python tools/sava/export_sava_onnx.py --out tools/sava/build

The weights (Apache 2.0) download from huggingface.co/inworld/sava. The script
writes sava_step.onnx (fp32, 103 MB) and sava_step_int8.onnx (27 MB, int8
weights except the mel front end), and checks both against SAVA's own streaming
session.
"""

import argparse
import os
import re

import numpy as np
import torch

from inworld import sava
from inworld.sava import config
from inworld.sava import mel
from inworld.sava import model as model_lib
from inworld.sava import modules


def _features_without_unfold(self, pcm: torch.Tensor) -> torch.Tensor:
    """``MelFrontend.features`` with ``unfold`` written as slices.

    The ONNX exporter turns ``unfold`` into a concat along the wrong axis, so
    the exported DFT matmul gets the wrong shape."""
    dft, bank = self._bases_on(pcm.device)
    hops = (pcm.shape[-1] - config.MEL_FFT) // config.MEL_HOP + 1
    windows = torch.stack([
        pcm[:, i * config.MEL_HOP:i * config.MEL_HOP + config.MEL_FFT]
        for i in range(hops)
    ], dim=1).float()
    real, imag = torch.matmul(windows, dft).chunk(2, dim=-1)
    log_mel = mel.log_compress(torch.matmul(real * real + imag * imag, bank))
    batch = log_mel.shape[0]
    return log_mel.reshape(batch, hops // config.MEL_HOPS_PER_FRAME, -1)


_original_features = mel.MelFrontend.features


class Step(torch.nn.Module):
    """One frame of ``SavaModel.step`` with the state as flat tensors.

    Every attention cache's offset equals the number of frames stepped, so a
    single ``frames`` counter stands for all of them."""

    def __init__(self, model: model_lib.SavaModel):
        super().__init__()
        self.model = model
        self.mamba = [i for i, kind in enumerate(model.cfg.layers) if kind == "mamba"]
        self.attention = [i for i, kind in enumerate(model.cfg.layers) if kind == "attention"]

    def forward(self, pcm, frames, prev_speech, frontend, mixer_tail, conv_tails,
                ssms, keys, values):
        offset = frames.reshape(())
        trunk = [None] * len(self.model.trunk)
        for n, i in enumerate(self.mamba):
            trunk[i] = modules.MambaState(conv_tail=conv_tails[n], ssm=ssms[n])
        for n, i in enumerate(self.attention):
            trunk[i] = modules.AttentionState(keys=keys[n], values=values[n], offset=offset)
        last = len(self.attention)
        state = model_lib.StreamState(
            frontend=frontend,
            mixer=modules.MixerState(tail=mixer_tail),
            trunk=trunk,
            vad=modules.AttentionState(keys=keys[last], values=values[last], offset=offset),
            turn=modules.AttentionState(keys=keys[last + 1], values=values[last + 1],
                                        offset=offset),
            prev_speech=prev_speech,
            frames=offset)
        out, state = self.model.step(pcm, state)
        speech = out.lookahead if out.lookahead is not None else out.speech
        new_keys = torch.stack([state.trunk[i].keys for i in self.attention] +
                               [state.vad.keys, state.turn.keys])
        new_values = torch.stack([state.trunk[i].values for i in self.attention] +
                                 [state.vad.values, state.turn.values])
        return (torch.sigmoid(speech[:, 0]), torch.sigmoid(out.turn[:, 0, 0]),
                state.frames.reshape(1), state.prev_speech, state.frontend,
                state.mixer.tail,
                torch.stack([state.trunk[i].conv_tail for i in self.mamba]),
                torch.stack([state.trunk[i].ssm for i in self.mamba]),
                new_keys, new_values)


INPUTS = ["pcm", "frames", "prev_speech", "frontend", "mixer_tail", "conv_tails",
          "ssms", "keys", "values"]
OUTPUTS = ["speech", "turn", "frames_out", "prev_speech_out", "frontend_out",
           "mixer_tail_out", "conv_tails_out", "ssms_out", "keys_out", "values_out"]


def initial_state(model: model_lib.SavaModel, step: Step):
    s = model.init_state(1, torch.device("cpu"), torch.float32)
    attention = [s.trunk[i] for i in step.attention] + [s.vad, s.turn]
    return [
        torch.zeros(1, dtype=torch.long),
        s.prev_speech,
        s.frontend,
        s.mixer.tail,
        torch.stack([s.trunk[i].conv_tail for i in step.mamba]),
        torch.stack([s.trunk[i].ssm for i in step.mamba]),
        torch.stack([a.keys for a in attention]),
        torch.stack([a.values for a in attention]),
    ]


def speech_like_audio(seconds: float = 6.0) -> np.ndarray:
    """Harmonic bursts with pauses, so the check sees speech and silence."""
    rate = config.SAMPLE_RATE
    t = np.arange(int(seconds * rate)) / rate
    pitch = 140 + 30 * np.sin(2 * np.pi * 3 * t)
    voice = sum(np.sin(2 * np.pi * k * np.cumsum(pitch) / rate) / k for k in range(1, 8))
    gate = ((t % 2.0) < 1.2).astype(np.float32)
    rng = np.random.default_rng(0)
    return (0.2 * voice * gate + 0.003 * rng.standard_normal(t.size)).astype(np.float32)


def run_onnx(path: str, audio: np.ndarray, state0) -> tuple[np.ndarray, np.ndarray]:
    import onnxruntime  # pylint: disable=import-outside-toplevel
    session = onnxruntime.InferenceSession(path, providers=["CPUExecutionProvider"])
    state = [x.numpy() for x in state0]
    speech, turn = [], []
    for start in range(0, audio.size - config.FRAME_SAMPLES + 1, config.FRAME_SAMPLES):
        pcm = audio[None, start:start + config.FRAME_SAMPLES]
        out = session.run(None, dict(zip(INPUTS, [pcm] + state)))
        speech.append(out[0][0])
        turn.append(out[1][0])
        state = out[2:]
    return np.array(speech), np.array(turn)


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", default="tools/sava/build")
    parser.add_argument("--model", default="inworld/sava")
    parser.add_argument("--audio", help="16 kHz mono WAV for the parity check")
    args = parser.parse_args()
    os.makedirs(args.out, exist_ok=True)

    model = sava.load(args.model).float().eval()
    step = Step(model).eval()
    state0 = initial_state(model, step)
    pcm = torch.zeros(1, config.FRAME_SAMPLES)
    fp32 = os.path.join(args.out, "sava_step.onnx")
    with torch.inference_mode():
        mel.MelFrontend.features = _features_without_unfold
        try:
            torch.onnx.export(step, (pcm, *state0), fp32, input_names=INPUTS,
                              output_names=OUTPUTS, opset_version=17, dynamo=False)
        finally:
            mel.MelFrontend.features = _original_features

    from onnxruntime.quantization import QuantType, quantize_dynamic  # pylint: disable=import-outside-toplevel
    import onnx  # pylint: disable=import-outside-toplevel
    int8 = os.path.join(args.out, "sava_step_int8.onnx")
    # Only the layers' weights are quantized. The unnamed MatMuls are the mel
    # front end's DFT and filterbank, whose precision the log-mel needs.
    # Per-channel scales and fp16 both drift further from fp32 on real speech.
    graph = onnx.load(fp32, load_external_data=False).graph
    keep_fp32 = [n.name for n in graph.node
                 if n.op_type == "MatMul" and re.fullmatch(r"/MatMul(_\d+)?", n.name)]
    quantize_dynamic(fp32, int8, weight_type=QuantType.QInt8, per_channel=False,
                     nodes_to_exclude=keep_fp32)

    audio = (np.asarray(sava.load_audio(args.audio), dtype=np.float32)
             if args.audio else speech_like_audio())
    audio = audio[:audio.size - audio.size % config.FRAME_SAMPLES]
    reference = sava.Session(model).push(audio)
    for name, path in (("fp32", fp32), ("int8", int8)):
        speech, turn = run_onnx(path, audio, state0)
        print(f"{name}: {os.path.getsize(path) / 1e6:.1f} MB, "
              f"max |speech diff| {np.abs(speech - reference.speech).max():.4f}, "
              f"max |turn diff| {np.abs(turn - reference.turn).max():.4f}, "
              f"speech frames {int((speech >= 0.5).sum())}/{speech.size} "
              f"(reference {int((reference.speech >= 0.5).sum())})")


if __name__ == "__main__":
    main()

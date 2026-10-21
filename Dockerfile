FROM python:3.12.3-slim-bookworm

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

RUN apt-get update \
    && apt-get install -y --no-install-recommends ffmpeg ca-certificates \
    && rm -rf /var/lib/apt/lists/* \
    && useradd --create-home --uid 10001 appuser

WORKDIR /app
COPY requirements.txt .
RUN pip install --upgrade pip \
    && pip install -r requirements.txt

COPY moviebox_api ./moviebox_api
COPY moviebox_api_server.py .
COPY .python-version .

RUN chown -R appuser:appuser /app
USER appuser

EXPOSE 10000
CMD ["sh", "-c", "uvicorn moviebox_api_server:app --host 0.0.0.0 --port ${PORT:-10000} --proxy-headers"]

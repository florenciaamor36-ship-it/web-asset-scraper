FROM mcr.microsoft.com/playwright/python:v1.52.0-noble

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    MAX_CONCURRENT_CAPTURES=2 \
    MAX_ASSETS=300 \
    MAX_ASSET_BYTES=16777216 \
    MAX_TOTAL_BYTES=100663296

WORKDIR /app
COPY capture-service/requirements.txt ./requirements.txt
RUN pip install --no-cache-dir -r requirements.txt
COPY capture-service ./capture-service
COPY index.html ./index.html
WORKDIR /app/capture-service
EXPOSE 8000
CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8000"]
